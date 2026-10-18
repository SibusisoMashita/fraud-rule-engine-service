package za.co.sibusiso.fraud.policy.definition;

import java.util.Objects;
import java.util.function.BiConsumer;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.RuleId;

public record RuleDefinition<C extends RuleSettings>(String configurationKey, RuleId id,
        String algorithmVersion, Class<C> settingsType, BiConsumer<C, CurrencyPolicy> validator) {
    public RuleDefinition {
        ContractChecks.require(configurationKey != null && configurationKey.matches("[a-z][A-Za-z0-9]{0,63}"),
                "Invalid rule configuration key");
        Objects.requireNonNull(id);
        algorithmVersion = ContractChecks.text(algorithmVersion, 32);
        Objects.requireNonNull(settingsType);
        Objects.requireNonNull(validator);
    }
}
