package za.co.sibusiso.fraud.contracts;

import java.util.LinkedHashMap;
import java.util.Map;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.TransactionEvent;

public final class CanonicalTransaction {
    private CanonicalTransaction() { }

    public static String hash(String scope, TransactionEvent event) {
        return CanonicalJson.sha256("fraud-request-v1", document(scope, event));
    }

    public static Map<String, Object> document(String scope, TransactionEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("canonicalizationVersion", 1);
        fields.put("scope", ContractChecks.identifier(scope));
        fields.put("eventId", event.eventId());
        fields.put("customerId", event.customerId());
        fields.put("instrumentId", event.instrumentId());
        fields.put("transactionType", event.transactionType().name());
        fields.put("status", event.status().name());
        fields.put("channel", event.channel().name());
        fields.put("amount", event.money().amount().stripTrailingZeros().toPlainString());
        fields.put("currency", event.money().currency());
        fields.put("category", event.category());
        fields.put("occurredAt", event.occurredAt().toString());
        var location = event.physicalLocation();
        if (location == null) fields.put("physicalLocation", null);
        else {
            var physical = new LinkedHashMap<String, Object>();
            physical.put("latitude", location.latitude());
            physical.put("longitude", location.longitude());
            physical.put("accuracyKm", location.accuracyKm());
            physical.put("source", location.source().name());
            physical.put("city", location.city());
            fields.put("physicalLocation", java.util.Collections.unmodifiableMap(physical));
        }
        return java.util.Collections.unmodifiableMap(fields);
    }
}
