package za.co.sibusiso.fraud.policy;

import za.co.sibusiso.fraud.domain.ContractChecks;

public interface ActivePolicyStore {
    record Reference(long revision, String policyHash) {
        public Reference {
            ContractChecks.require(revision > 0 && policyHash != null && policyHash.matches("[a-f0-9]{64}"),
                    "Invalid active policy reference");
        }
    }

    Reference active();
    String document(String policyHash);
}
