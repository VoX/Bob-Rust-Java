package com.bobrust.generator.tiler;

/**
 * One square stamp of the palettized plan: an axis-aligned {@code side × side}
 * block of virtual cells anchored at ({@code cellX}, {@code cellY}) painted in
 * palette color {@code colorIndex}.
 */
public record PixelStamp(int cellX, int cellY, int side, int colorIndex) {
}
