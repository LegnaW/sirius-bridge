package io.sirius.bridge;

import com.google.gson.JsonObject;

/** Pure wire-shape and health-transition logic for the CRITICAL damage event. */
final class DamageContracts {

    record EntityFact(String name, String type, String uuid) {
    }

    record SourceFact(String damageType, EntityFact attacker, EntityFact directEntity) {
    }

    private DamageContracts() {
    }

    /** Returns null unless this observation represents an actual health decrease. */
    static JsonObject damageData(Float previousHealth, float currentHealth, SourceFact source) {
        if (previousHealth == null || !(currentHealth < previousHealth)) {
            return null;
        }
        JsonObject data = new JsonObject();
        data.addProperty("health_before", previousHealth);
        data.addProperty("health_after", currentHealth);
        data.addProperty("amount", previousHealth - currentHealth);
        data.addProperty("damage_type", source != null ? source.damageType() : "unknown");
        data.add("attacker", entityData(source != null ? source.attacker() : null));
        data.add("direct_entity", entityData(source != null ? source.directEntity() : null));
        return data;
    }

    static final class Attributor {
        static final long GRACE_MS = 250;

        private Float previousHealth;
        private Float pendingBefore;
        private float pendingAfter;
        private long pendingSince = -1;
        private long consumedSequence;

        JsonObject sample(float health, SourceFact source, long sourceSequence, long sourceAt, long now) {
            if (previousHealth != null && health < previousHealth) {
                if (pendingSince < 0) {
                    pendingSince = now;
                    pendingBefore = previousHealth;
                }
                pendingAfter = health;
            }
            previousHealth = health;
            if (pendingSince < 0) {
                return null;
            }
            if (source != null && sourceSequence > consumedSequence
                    && sourceAt >= pendingSince - GRACE_MS && sourceAt <= now) {
                consumedSequence = sourceSequence;
                return complete(source);
            }
            if (now - pendingSince >= GRACE_MS) {
                return complete(null);
            }
            return null;
        }

        private JsonObject complete(SourceFact source) {
            JsonObject data = damageData(pendingBefore, pendingAfter, source);
            pendingBefore = null;
            pendingSince = -1;
            return data;
        }

        void reset() {
            previousHealth = null;
            pendingBefore = null;
            pendingSince = -1;
            consumedSequence = 0;
        }
    }

    private static JsonObject entityData(EntityFact fact) {
        if (fact == null) {
            return null;
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", fact.name());
        data.addProperty("type", fact.type());
        data.addProperty("uuid", fact.uuid());
        return data;
    }
}
