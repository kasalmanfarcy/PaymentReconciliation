package com.reconflow.paymentreconflow.tenancy;

import java.util.Map;

/** A {@link CrossTenantCase} body sent as {@code multipart/form-data}: one file part plus form fields. */
public record MultipartBody(String fileName, String contentType, byte[] content, Map<String, String> params) {}
