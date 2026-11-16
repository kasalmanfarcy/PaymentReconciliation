package com.reconflow.paymentreconflow.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7Uuids() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 9562 variant
    }

    @Test
    void idsAreStrictlyIncreasingAcrossManyCalls() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId().toString());
        }

        // Compare the canonical lower-case hex strings: their lexicographic order is the
        // unsigned byte order PostgreSQL uses for uuid. (Historically UUID.compareTo compared
        // the two halves as signed longs, which is not the same order.)
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
        }
    }
}
