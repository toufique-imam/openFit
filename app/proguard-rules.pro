# Room and kotlinx.serialization ship consumer rules. Preserve the names used by
# HealthStore/RecordCodec for record type keys across debug, release and upgrades.
-keepnames class androidx.health.connect.client.records.**

# Serializers are generated and called directly. Do not keep every domain/UI
# class: their serialized field names are provided by the serialization plugin.
