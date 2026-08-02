package org.jellyfin.androidtv.ui

import androidx.annotation.DrawableRes

open class GridButton @JvmOverloads constructor(
	val id: Int,
	val text: String,
	@DrawableRes val imageRes: Int? = null,
	val contentDescription: String = text,
) {
	override fun toString() = text
}
