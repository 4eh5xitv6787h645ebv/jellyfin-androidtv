package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class CanopyActionsPlacement(
	override val nameRes: Int,
) : PreferenceEnum {
	/**
	 * Native detail buttons next to Play/Watched, in the style of the other
	 * item actions.
	 */
	BUTTONS(R.string.canopy_placement_buttons),

	/**
	 * Entries in the "Other options" overflow menu.
	 */
	OTHER_OPTIONS(R.string.canopy_placement_other_options),

	/**
	 * The classic dedicated Actions row below the item details, including
	 * status text.
	 */
	ROW(R.string.canopy_placement_row),
}
