package net.minecheck.platform.api;

import java.util.List;

/**
 * One ingredient slot of a recipe as shown by a recipe viewer — a "ghost" slot: the viewer draws it
 * next to the container, it is not part of the container's real slots.
 *
 * @param index the slot's position in the recipe's ingredient list (0-based)
 * @param itemIds the item registry ids that satisfy the slot (a tag-backed slot lists its members;
 *     empty for a slot the viewer could not resolve to items)
 */
public record GhostSlot(int index, List<String> itemIds) {}
