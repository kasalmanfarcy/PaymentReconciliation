# System Design: Payment Reconciliation & Exception Resolution Platform

We're going to design this as a real backend engineering project, not just a collection of REST APIs. The goal is to understand the business problem, design the architecture, model the data, define the APIs, and then implement it step by step.

Your project will be called ReconFlow — a payment reconciliation platform for businesses that need to compare their internal payment records against payment gateway settlements.

We'll use Java and Spring Boot as the core, with PostgreSQL for financial records. Kafka and other infrastructure will come later, when the basic system works.

## 1. First, understand the real-world problem

Imagine an online business sells products to customers. A customer pays ₹1,000 through a payment gateway.

Three different systems may now contain information about this transaction:

- Your business system: records the order as paid.
- Payment gateway: records that it processed the payment.
- Bank or settlement account: records the money that was actually deposited, potentially after fees or other adjustments.

These records do not always agree.

For example, a gateway might report a successful payment, but the corresponding settlement could be delayed. A transaction might appear twice in an imported file. A payment could have a different reference in two systems.

Finance teams need to identify and investigate these differences instead of manually comparing thousands of records in spreadsheets.

That is the problem ReconFlow solves.

## A simple example

Suppose your business processes five payments.

| Transaction | Internal ledger | Gateway file | Result    |
| ----------- | --------------- | ------------ | --------- |
| TXN-101     | ₹1,000          | ₹1,000       | Matched   |
| TXN-102     | ₹500            | Missing      | Unmatched |
| TXN-103     | ₹750            | ₹700         | Mismatch  |
| TXN-104     | ₹250            | ₹250 twice   | Duplicate |
| TXN-105     | ₹300            | ₹300         | Matched   |

Illustrative data. In a real system, matching must consider the transaction type, currency, settlement date, fees, and the provider's data format.

ReconFlow should answer four questions:

1. Which transactions match?
2. Which transactions are missing, duplicated, or inconsistent?
3. Why did each discrepancy occur?
4. Who investigated it, and how was it resolved?

That's the product. Everything else we design supports those four outcomes.

## 2. Understand the complete system

At a high level, ReconFlow has six major stages.

### 01

Data ingestion

Import internal transactions and gateway/bank settlement files.

### 02

Validation and normalization

Validate the file, standardize fields, and identify duplicate imports.

### 03

Reconciliation engine

Compare records using reliable matching rules.

### 04

Exception management

Create cases for missing records, duplicates, and amount differences.

### 05

Investigation and resolution

Let authorized users assign, investigate, and resolve exceptions.

### 06

Reporting and audit

Show reconciliation results, financial totals, and a history of actions.

For the first version, users will upload CSV files manually. Later, you can add scheduled imports, APIs, and event-driven processing.

## 3. System architecture

Start with a modular monolith. That means one Spring Boot application with clearly separated modules, rather than many independently deployed microservices.

This is a deliberate engineering decision. You can demonstrate good system design without having to manage distributed deployment before you understand the business logic.

Finance user / Administrator

Browser or API client

Spring Boot REST API

Authentication · Authorization · Request validation

### ReconFlow application modules

Ingestion

Files and imports

Ledger

Normalized records

Reconciliation

Matching engine

Exceptions

Investigation workflow

Reporting and audit

Results, history, and accountability

PostgreSQL

Persistent records and transactional consistency

### Later: asynchronous processing

Kafka

Events

Worker

Background jobs

Add only when there is a demonstrated need for asynchronous or independently retried work.

### Why these choices?

- Spring Boot: API endpoints, business rules, transaction boundaries, and security.
- PostgreSQL: financial records require durable storage, constraints, and consistent updates.
- Kafka (later): useful for processing large imports asynchronously and recovering from worker failures.
- Redis (optional, later): not necessary for the first version; introduce it only if a concrete caching or coordination problem justifies it.
- Docker: reproducible local development and deployment.

The initial system can run on your MacBook with Java, PostgreSQL, and Docker. No cloud infrastructure is required to begin.

## 4. The data model: the most important design decision

Before designing controllers, decide what information the system needs to preserve.

I would start with these six entities.

1\. Organization

Represents a business using ReconFlow.

`id`, `name`, `createdAt`

2\. ImportBatch

Tracks an uploaded CSV file, its processing status, and its row counts.

`id`, `organizationId`, `sourceType`, `status`, `totalRows`, `errorRows`

3\. TransactionRecord

A normalized transaction from either the internal ledger or an external provider.

`id`, `organizationId`, `batchId`, `sourceType`, `externalReference`, `amount`, `currency`, `status`, `transactionDate`

4\. ReconciliationRun

One execution of a matching process for a defined period and source combination.

`id`, `organizationId`, `status`, `startedAt`, `completedAt`

5\. ExceptionCase

A discrepancy that requires review or resolution.

`id`, `organizationId`, `runId`, `type`, `status`, `assignedTo`, `resolution`

6\. AuditEvent

An append-only record of important actions and state changes.

`id`, `organizationId`, `actorId`, `action`, `entityType`, `entityId`, `createdAt`

These are the logical entities, not necessarily the final database schema. You'll need additional fields, constraints, and indexes as the design develops.

### How the entities relate

- One organization has many import batches.
- One import batch contains many transaction records.
- One organization has many reconciliation runs.
- One reconciliation run creates or updates exception cases.
- Audit events record important changes across the workflow.

A further design requirement: when a transaction from one source matches a transaction from another, the system needs a persistent match record linking the two. For a production-quality design, I would add a `ReconciliationMatch` entity rather than storing the entire match only inside the run.

Also, use `BigDecimal` in Java and an appropriate PostgreSQL `NUMERIC` type for monetary values. Don't use `double` or `float` for financial amounts.

## 5. How the reconciliation engine works

This is the core intellectual component of the project.

Suppose you import these records:

Internal ledger

| Reference | Amount   | Currency |
| --------- | -------- | -------- |
| PAY-101   | 1,000.00 | AED      |
| PAY-102   | 500.00   | AED      |
| PAY-103   | 750.00   | AED      |

Gateway settlement

| Reference | Amount   | Currency |
| --------- | -------- | -------- |
| PAY-101   | 1,000.00 | AED      |
| PAY-103   | 700.00   | AED      |
| PAY-104   | 200.00   | AED      |

Your engine compares the two datasets.

1. Normalize: standardize references, currencies, timestamps, and statuses without destroying the original imported values.
2. Match exact references: find records with the same reference, compatible currency, and matching transaction type.
3. Compare financial values: if the references match but the amounts differ, flag an amount mismatch instead of silently marking the records matched.
4. Identify missing records: a transaction found only in the internal ledger or only in the gateway file becomes an unmatched record.
5. Detect duplicates: identify repeated source records and repeated imports. Don't arbitrarily select one duplicate and discard the rest.
6. Persist the result: save match records, unmatched items, exception cases, and summary counts for the reconciliation run.

### Important: real payment reconciliation is more complicated

A bank settlement may combine multiple payments, deduct fees, include refunds, or arrive on a different date from the original transaction.

So we should eventually support:

- Exact one-to-one matching.
- One-to-many and many-to-one matching for aggregated settlements.
- Separate transaction types for payments, refunds, chargebacks, and fees.
- Currency-aware comparisons.
- Configurable settlement windows.
- Manual review when a match is ambiguous.

Don't start by trying to solve every scenario. Implement deterministic one-to-one matching first and add more complex rules after testing the basics.

## 6. End-to-end workflow: from upload to resolution

Let's walk through what actually happens when a user uses the application.

## Example: importing a settlement file

1. Upload

   The user uploads `gateway-settlement.csv`. The API checks the file size, file type, organization access, and required headers.
2. Create an import batch

   Save an import record with a status such as `RECEIVED`. Store the original file or its durable reference, so the import can be audited and reprocessed.
3. Parse and validate rows

   Check required columns, amount formats, currencies, dates, and duplicate records. Record row-level errors so one malformed row doesn't erase the entire file's diagnostic information.
4. Normalize and persist

   Convert valid rows into the common transaction model. Preserve source references and original values. Use database constraints to prevent duplicate imports and transaction records where applicable.
5. Run reconciliation

   Compare the internal ledger with the imported gateway records for the chosen date range. Store matches and create exceptions for discrepancies.
6. Review exceptions

   A finance user opens an exception, examines both source records, adds a note, assigns ownership, and records the resolution.
7. Report results

   Display matched totals, unmatched amounts, mismatch counts, import errors, and unresolved exceptions. Provide a traceable history of the run.

### What happens if something fails?

This is where your project becomes much more than CRUD.

- If the same file is uploaded twice, the system should recognize the duplicate according to a documented import policy.
- If a process crashes halfway through an import, it should resume or safely reprocess without creating duplicate financial records.
- If reconciliation runs twice, it should not create duplicate matches or duplicate active exception cases.
- If two users try to resolve the same exception simultaneously, the system should prevent one update from silently overwriting the other.
- If a file contains invalid rows, the batch should expose the errors and make it clear which rows were successfully processed.

These cases should drive your database constraints, transaction boundaries, idempotency rules, and tests.

## 7. Design the API

Start with a small set of endpoints grouped by business capability.

| Method and endpoint                    | Purpose                               |
| -------------------------------------- | ------------------------------------- |
| `POST /api/v1/imports`                 | Upload a transaction file             |
| `GET /api/v1/imports/{id}`             | Check import status and errors        |
| `GET /api/v1/transactions`             | Search and filter transaction records |
| `POST /api/v1/reconciliation-runs`     | Start a reconciliation run            |
| `GET /api/v1/reconciliation-runs/{id}` | View results and run status           |
| `GET /api/v1/exceptions`               | List and filter exceptions            |
| `GET /api/v1/exceptions/{id}`          | View the details of an exception      |
| `PATCH /api/v1/exceptions/{id}`        | Assign or update an exception         |
| `POST /api/v1/exceptions/{id}/resolve` | Resolve an exception with a reason    |
| `GET /api/v1/reports/reconciliation`   | Retrieve summary metrics              |

For the first version, you do not need to implement every endpoint. Build the import workflow first, then the reconciliation engine, and then exception management.

For long-running imports and reconciliation jobs, the API should generally return `202 Accepted` with a job or run ID. The client can poll the status endpoint while processing continues.

## 8. Define statuses before writing the code

Explicit state transitions prevent confusing behavior and make testing much easier.

### Import batch lifecycle

RECEIVED

PROCESSING

COMPLETED

Alternative outcomes: FAILED or COMPLETED_WITH_ERRORS. A retry should be an explicit, auditable operation.

### Reconciliation run lifecycle

PENDING

RUNNING

COMPLETED

Alternative outcome: FAILED. Retrying a failed run should not duplicate its previously committed matches.

### Exception lifecycle

OPEN

IN_REVIEW

RESOLVED

A reopened exception should retain its earlier resolution history. Only authorized users should be able to resolve or reopen cases.

These are starting states, not immutable requirements. Document valid transitions and decide which transitions require a reason, authorization, or audit event.

## 9. Non-functional requirements: what makes this a serious backend project?

System design isn't only about which framework you use. You also need to define how the application should behave.

For the first version, set measurable targets as engineering goals, then test whether the implementation meets them.

| Concern          | Initial design decision                                         |
| ---------------- | --------------------------------------------------------------- |
| Correctness      | Use deterministic matching rules and explicit mismatch outcomes |
| Data integrity   | PostgreSQL transactions, foreign keys, unique constraints       |
| Duplicate safety | Idempotent imports and reconciliation runs                      |
| Security         | Organization-level access control and role-based permissions    |
| Auditability     | Append-only audit events for important actions                  |
| Performance      | Paginated queries, appropriate indexes, batch inserts           |
| Recovery         | Retryable jobs, recorded failures, safe reprocessing            |
| Observability    | Structured logs, processing metrics, health checks              |
| Testing          | Unit, integration, concurrency, and failure-recovery tests      |

### Multi-tenancy is especially important

ReconFlow is intended for multiple businesses, so every business-owned record needs an organization boundary.

For example, a user from Organization A must not be able to fetch an exception belonging to Organization B by guessing its ID.

Do not rely only on the frontend hiding other organizations' records. Enforce tenant access in the backend and test it explicitly.

### Don't add infrastructure just to look advanced

For the initial version, a scheduled job or synchronous service method may be enough to run reconciliation. Once you need durable background processing, Kafka can help distribute work and handle retries.

Similarly, Redis isn't mandatory. Good database design and correct business logic are more valuable than using every popular technology.

## 10. How we'll build it from zero

Follow these milestones in order. Each one produces a working result.

## 01

Requirements and system design

Define the users, business rules, matching scenarios, data model, API contracts, and architecture. Draw the architecture and entity relationship diagrams before implementation.

Start here

## 02

Project foundation

Set up Java, Spring Boot, Maven, PostgreSQL, database migrations, configuration, and automated tests.

## 03

Transaction ingestion

Upload CSV files, validate rows, normalize records, store import batches, and handle duplicate files safely.

## 04

Reconciliation engine

Implement exact matching, unmatched records, amount mismatches, duplicate detection, and reconciliation reports.

## 05

Exception management

Add assignment, investigation notes, resolution reasons, authorization, and audit history.

## 06

Reliability and performance

Add safe reprocessing, concurrency protection, Kafka where justified, integration tests, metrics, and benchmarks.

## 07

Deployment and portfolio

Dockerize the application, deploy it, document the architecture and trade-offs, and publish a demo with reproducible test data.

## 11. Your first system-design exercise

Before coding, write a short design document answering these questions:

1. Users: Who uploads the data, who investigates discrepancies, and who administers organizations?
2. Inputs: What fields are required in internal-ledger and gateway CSV files?
3. Matching rules: What makes two transactions a match? What makes them a mismatch?
4. Data model: How do organizations, imports, transactions, reconciliation runs, matches, and exceptions relate?
5. Failure handling: What happens when an import or reconciliation run fails halfway through?
6. Security: How do we stop users from accessing another organization's financial records?
7. Scale: How would you process 50,000 records efficiently, and what measurements would tell you the system needs asynchronous processing?

Make assumptions explicit. For example, the first version will handle one-to-one payment matching, use one currency per transaction, accept CSV uploads, and use synthetic data. Later, you can expand these assumptions.

You don't need perfect answers immediately. The point is to learn to identify design decisions and their consequences.

## 12. The first deliverable

Create a `docs` directory in your project and plan these documents:

```
reconflow/
├── docs/
│   ├── requirements.md
│   ├── architecture.md
│   ├── database-design.md
│   ├── api-design.md
│   └── reconciliation-rules.md
├── src/
├── pom.xml
└── README.md
```

Start with `requirements.md`. Write the problem statement, users, functional requirements, non-functional requirements, and initial scope. Then draw the high-level architecture and database relationships.

My recommendation: don't start by asking an AI to generate the entire application. Design one workflow yourself, implement it, test it, and understand the failure cases. That's what will help you answer system-design and backend interviews confidently.

When you're ready, we can review your requirements and design together before you write the first controller.