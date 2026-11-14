package org.dancesonic.player.dance

// The meter rules shared by the detail view, its dialog and the send. A meter is a top-level value such as `8/4`;
// a sub-meter is a value under its meter, such as `3+3+2`.

/** The meter as shown, such as `8/4 - 3+3+2`, or `8/4` without a sub-meter. Null when the recording has no meter. */
fun meterLabel(
    meter: String?,
    subMeter: String?,
): String? =
    when {
        meter == null -> null
        subMeter == null -> meter
        else -> "$meter - $subMeter"
    }

/** The meters of DanceBrainz, those without a parent, by name. */
fun topMeters(values: List<MeterValue>): List<MeterValue> = values.filter { it.parentId == null }.sortedBy { it.name }

/** The sub-meters under [meter], by name. */
fun subMetersOf(
    values: List<MeterValue>,
    meter: MeterValue,
): List<MeterValue> = values.filter { it.parentId == meter.tagId }.sortedBy { it.name }

/**
 * The value a queued meter change names: a meter when [parentName] is null, otherwise the sub-meter [name] under the
 * meter [parentName]. Null when DanceBrainz no longer has it.
 */
fun resolveMeter(
    values: List<MeterValue>,
    name: String,
    parentName: String?,
): MeterValue? {
    if (parentName == null) return topMeters(values).firstOrNull { it.name == name }
    val parent = topMeters(values).firstOrNull { it.name == parentName } ?: return null
    return subMetersOf(values, parent).firstOrNull { it.name == name }
}
