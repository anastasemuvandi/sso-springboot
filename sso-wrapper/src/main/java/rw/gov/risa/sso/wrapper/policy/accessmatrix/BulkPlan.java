package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.List;
import java.util.Map;

/** The outcome of {@link BulkPlanner#plan}: refused whole, or a per-item plan. */
public sealed interface BulkPlan {

    /** 403 for the whole request; nothing is written. Lists are sorted. */
    record Forbidden(BulkRequestRefusal refusal, List<String> outside) implements BulkPlan {
        public Forbidden {
            outside = List.copyOf(outside);
        }
    }

    /**
     * Per-item plan.
     *
     * @param rejected items already decided (duplicates and rejections), in reference order
     * @param writes client id -> account holders to admit/revoke in one policy write, in client order
     * @param toProvision employee number -> clients to grant through provisioning (no account yet)
     */
    record Ready(List<ItemResult> rejected, Map<String, List<PlannedWrite>> writes,
                 Map<String, List<String>> toProvision) implements BulkPlan {
    }
}
