package com.keyx.app.ime

import com.keyx.app.layout.KeyType

/**
 * The space bar reaches a little past its drawn ends: a thumb that just clips the
 * comma or the period on its way to space gets the space. Letters page only, where
 * words are typed; the drawn keys do not move.
 */
object SpaceEdge {
    /** Share of the comma's or period's width, next to the space bar, that counts as space. */
    const val REACH = 0.12f

    /** [fromSpace] is how far into the key the touch is, from the side touching the space bar, 0 to 1. */
    fun claims(type: KeyType, fromSpace: Float): Boolean =
        (type == KeyType.COMMA || type == KeyType.PERIOD) && fromSpace in 0f..REACH
}
