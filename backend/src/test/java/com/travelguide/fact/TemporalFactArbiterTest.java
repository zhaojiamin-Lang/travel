package com.travelguide.fact;

import com.travelguide.fact.FactModels.FactCollection;
import com.travelguide.fact.FactModels.FactQuery;
import com.travelguide.fact.FactModels.FactRecord;
import com.travelguide.fact.FactModels.FactStatus;
import com.travelguide.fact.FactModels.SourceAuthority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalFactArbiterTest {

    private static final Instant AS_OF = Instant.parse("2026-10-29T04:00:00Z");
    private static final LocalDate TRIP_DATE = LocalDate.of(2026, 10, 29);

    private final TemporalFactArbiter arbiter = new TemporalFactArbiter();

    @Test
    void prefersOfficialLiveFactOverThirdPartyFact() {
        FactRecord official = fact(
                "f1", "amap-live", SourceAuthority.OFFICIAL_LIVE,
                Map.of("status", "OPEN"), Map.of(), null
        );
        FactRecord thirdParty = fact(
                "f2", "third-party", SourceAuthority.AUTHORIZED_THIRD_PARTY,
                Map.of("status", "CLOSED"), Map.of(), null
        );

        var result = arbiter.resolve(
                query(),
                new FactCollection(List.of(thirdParty, official), Map.of())
        );

        assertThat(result.status()).isEqualTo(FactStatus.VALID);
        assertThat(result.selected().factId()).isEqualTo("f1");
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void preservesConflictBetweenSameAuthoritySources() {
        FactRecord first = fact(
                "f1", "official-a", SourceAuthority.OFFICIAL_LIVE,
                Map.of("status", "OPEN"), Map.of(), null
        );
        FactRecord second = fact(
                "f2", "official-b", SourceAuthority.OFFICIAL_LIVE,
                Map.of("status", "CLOSED"), Map.of(), null
        );

        var result = arbiter.resolve(
                query(),
                new FactCollection(List.of(first, second), Map.of())
        );

        assertThat(result.status()).isEqualTo(FactStatus.CONFLICT);
        assertThat(result.selected()).isNull();
        assertThat(result.conflicts()).extracting(FactRecord::factId).containsExactlyInAnyOrder("f1", "f2");
    }

    @Test
    void marksExpiredOnlyFactsAsStale() {
        FactRecord expired = fact(
                "f1", "official-live", SourceAuthority.OFFICIAL_LIVE,
                Map.of("status", "OPEN"), Map.of(),
                AS_OF.minusSeconds(60)
        );

        var result = arbiter.resolve(
                query(),
                new FactCollection(List.of(expired), Map.of())
        );

        assertThat(result.status()).isEqualTo(FactStatus.STALE);
        assertThat(result.selected()).isNull();
        assertThat(result.staleFacts()).hasSize(1);
    }

    @Test
    void prefersDateSpecificRuleOverGenericRule() {
        FactRecord generic = fact(
                "f1", "official-static", SourceAuthority.OFFICIAL_STATIC,
                Map.of("close", "17:00"), Map.of(), null
        );
        FactRecord special = fact(
                "f2", "official-live", SourceAuthority.OFFICIAL_LIVE,
                Map.of("close", "21:00"), Map.of("applicableDate", TRIP_DATE.toString()), null
        );

        var result = arbiter.resolve(
                query(),
                new FactCollection(List.of(generic, special), Map.of())
        );

        assertThat(result.status()).isEqualTo(FactStatus.VALID);
        assertThat(result.selected().factId()).isEqualTo("f2");
    }

    private FactQuery query() {
        return new FactQuery("poi:bund", "OPEN_STATUS", AS_OF, TRIP_DATE, Map.of());
    }

    private FactRecord fact(
            String id,
            String sourceId,
            SourceAuthority authority,
            Map<String, Object> value,
            Map<String, String> conditions,
            Instant validTo
    ) {
        return new FactRecord(
                id,
                "poi:bund",
                "OPEN_STATUS",
                value,
                sourceId,
                sourceId,
                authority,
                AS_OF.minusSeconds(30),
                AS_OF.minusSeconds(3600),
                validTo == null ? AS_OF.plusSeconds(3600) : validTo,
                conditions,
                0.95
        );
    }
}
