import { X } from "lucide-react";
import { type Category, type Tag, useSaveCategory, useSaveTag } from "../../api/categories";
import { readableTextOn } from "../../lib/color";
import { firstUnusedColor } from "../ColorPicker";
import { Picker } from "../Picker";

/** "Housing › Rent" for a sub-category, so two with the same name can be told apart. */
export function categoryLabel(category: Category, all: readonly Category[]): string {
  const parent = all.find((other) => other.id === category.parentId);
  return parent ? `${parent.name} › ${category.name}` : category.name;
}

/**
 * The category picker every form shares: type to find one, or add what was typed as a new
 * category without leaving the form (spec §1, "pickers, not free text").
 */
export function CategoryField({
  categories,
  value,
  onChange,
}: {
  categories: readonly Category[];
  value: number | null;
  onChange: (categoryId: number | null) => void;
}) {
  const add = useSaveCategory();
  const options = categories.map((category) => ({
    id: String(category.id),
    label: categoryLabel(category, categories),
    color: category.color,
  }));

  return (
    <div>
      <Picker
        label="Category (optional)"
        noun="category"
        placeholder="Type to search or add new"
        options={options}
        value={value === null ? null : String(value)}
        onChange={(id) => onChange(id === null ? null : Number(id))}
        onCreate={(text) =>
          add.mutate(
            { name: text, color: firstUnusedColor(categories.map((category) => category.color)), parentId: null },
            { onSuccess: (created) => onChange(created.id) },
          )
        }
        clearable
      />
      {add.error && (
        <p role="alert" className="mt-1 text-sm text-danger">
          {add.error.message}
        </p>
      )}
    </div>
  );
}

/** Any number of tags: a picker to add one (or create it), and the chosen ones as removable chips. */
export function TagsField({
  tags,
  value,
  onChange,
}: {
  tags: readonly Tag[];
  value: readonly number[];
  onChange: (tagIds: number[]) => void;
}) {
  const add = useSaveTag();
  const chosen = value.flatMap((tagId) => tags.find((tag) => tag.id === tagId) ?? []);
  const options = tags
    .filter((tag) => !value.includes(tag.id))
    .map((tag) => ({ id: String(tag.id), label: tag.name, color: tag.color }));

  return (
    <div>
      <Picker
        label="Tags (optional)"
        noun="tag"
        placeholder="Type to search or add new"
        options={options}
        value={null}
        onChange={(tagId) => tagId !== null && onChange([...value, Number(tagId)])}
        onCreate={(text) =>
          add.mutate(
            { name: text, color: firstUnusedColor(tags.map((tag) => tag.color)) },
            { onSuccess: (created) => onChange([...value, created.id]) },
          )
        }
      />
      {add.error && (
        <p role="alert" className="mt-1 text-sm text-danger">
          {add.error.message}
        </p>
      )}
      {chosen.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-2">
          {chosen.map((tag) => (
            <li
              key={tag.id}
              className="flex items-center gap-1 rounded-full py-1 pr-1 pl-3 text-sm font-semibold"
              style={{ backgroundColor: tag.color, color: readableTextOn(tag.color) }}
            >
              {tag.name}
              <button
                type="button"
                aria-label={`Remove tag ${tag.name}`}
                onClick={() => onChange(value.filter((tagId) => tagId !== tag.id))}
                className="flex size-8 items-center justify-center rounded-full"
              >
                <X aria-hidden className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
