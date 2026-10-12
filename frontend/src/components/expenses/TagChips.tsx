import type { Tag } from "../../api/categories";
import { readableTextOn } from "../../lib/color";

/**
 * An item's tags as small pills in each tag's own color; the words on them switch between
 * dark and light to stay readable on whatever color was picked (spec §1).
 */
export function TagChips({ tagIds, tags }: { tagIds: readonly number[]; tags: readonly Tag[] }) {
  const chosen = tagIds.flatMap((tagId) => tags.find((tag) => tag.id === tagId) ?? []);
  if (chosen.length === 0) {
    return null;
  }
  return (
    <span className="flex flex-wrap gap-1">
      {chosen.map((tag) => (
        <span
          key={tag.id}
          className="max-w-full truncate rounded-full px-2.5 py-0.5 text-sm font-semibold"
          style={{ backgroundColor: tag.color, color: readableTextOn(tag.color) }}
        >
          {tag.name}
        </span>
      ))}
    </span>
  );
}
