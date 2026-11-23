package com.mochiwall.app.core.mask

/**
 * 16-slot lazy ring buffer providing exactly 15 undo steps over 144x320 masks (max ~737 KB).
 * Slots are allocated on demand and released on [clear].
 */
class MaskHistoryBuffer {

    private val slots = arrayOfNulls<ByteArray>(MAX_SLOTS)
    private var baseIndex = 0
    private var currentIndex = -1
    private var topIndex = -1

    val canUndo: Boolean
        get() = currentIndex > baseIndex

    val canRedo: Boolean
        get() = currentIndex >= 0 && currentIndex < topIndex

    val undoCount: Int
        get() = if (currentIndex > baseIndex) currentIndex - baseIndex else 0

    val redoCount: Int
        get() = if (currentIndex >= 0 && topIndex > currentIndex) topIndex - currentIndex else 0

    fun initialize(initialMask: ByteArray) {
        require(initialMask.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes"
        }
        clear()
        baseIndex = 0
        currentIndex = 0
        topIndex = 0
        val slot = getOrAllocateSlot(0)
        System.arraycopy(initialMask, 0, slot, 0, MaskBrushRasterizer.MASK_BYTE_COUNT)
    }

    fun commit(currentMask: ByteArray) {
        require(currentMask.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes"
        }
        if (currentIndex < 0) {
            initialize(currentMask)
            return
        }

        currentIndex += 1
        topIndex = currentIndex

        if (currentIndex - baseIndex >= MAX_SLOTS) {
            baseIndex = currentIndex - MAX_SLOTS + 1
        }

        val slot = getOrAllocateSlot(slotOf(currentIndex))
        System.arraycopy(currentMask, 0, slot, 0, MaskBrushRasterizer.MASK_BYTE_COUNT)
    }

    fun undo(outMask: ByteArray): Boolean {
        require(outMask.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes"
        }
        if (!canUndo) return false
        currentIndex -= 1
        val slot = slots[slotOf(currentIndex)] ?: return false
        System.arraycopy(slot, 0, outMask, 0, MaskBrushRasterizer.MASK_BYTE_COUNT)
        return true
    }

    fun redo(outMask: ByteArray): Boolean {
        require(outMask.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes"
        }
        if (!canRedo) return false
        currentIndex += 1
        val slot = slots[slotOf(currentIndex)] ?: return false
        System.arraycopy(slot, 0, outMask, 0, MaskBrushRasterizer.MASK_BYTE_COUNT)
        return true
    }

    fun clear() {
        for (i in slots.indices) {
            slots[i] = null
        }
        baseIndex = 0
        currentIndex = -1
        topIndex = -1
    }

    private fun slotOf(logicalIndex: Int): Int {
        val mod = logicalIndex % MAX_SLOTS
        return if (mod < 0) mod + MAX_SLOTS else mod
    }

    private fun getOrAllocateSlot(slotIdx: Int): ByteArray {
        return slots[slotIdx] ?: ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT).also {
            slots[slotIdx] = it
        }
    }

    companion object {
        const val MAX_SLOTS = 16
        const val MAX_UNDO_STEPS = MAX_SLOTS - 1
    }
}
