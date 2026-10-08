import { type ReactNode, useId, useState } from "react";
import {
  type Category,
  type Tag,
  useCategories,
  useDeleteCategory,
  useDeleteTag,
  useSaveCategory,
  useSaveTag,
  useTags,
} from "../api/categories";
import { firstUnusedColor } from "../components/ColorPicker";
import { LabelForm } from "../components/categories/LabelForm";
import { BUTTON_PRIMARY, PAGE_TITLE } from "../components/ui";
import { readableTextOn } from "../lib/color";
import { formatCents } from "../lib/money";

/** What a form is open for: something new, something that exists, or closed. */
type FormState<T> = { existing?: T } | null;

/**
 * Categories & tags (design/categories.png). Categories are one per item, with
 * sub-categories; tags are any number per item, for building reports. Each row shows how
 * many recurring items use it and what they come to over a year; choosing a row (or its
 * color dot) opens it for editing, color picker included.
 */
export function CategoriesPage() {
  const categories = useCategories();
  const tags = useTags();
  const [categoryForm, setCategoryForm] = useState<FormState<Category>>(null);
  const [tagForm, setTagForm] = useState<FormState<Tag>>(null);

  return (
    <>
      <div className="mb-4 md:mb-6">
        <p className="text-sm font-semibold tracking-wide text-muted uppercase">Setup</p>
        <h1 className={PAGE_TITLE}>Categories &amp; tags</h1>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel
          title="Categories"
          about="One per item. Used for the dashboard pie chart and spending totals."
          addLabel="+ Category"
          onAdd={() => setCategoryForm({})}
        >
          {categories.isPending && <p className="text-muted">Loading your categories…</p>}
          {categories.isError && (
            <p role="alert" className="text-danger">
              {categories.error.message}
            </p>
          )}
          {categories.data?.length === 0 && (
            <p className="text-muted">
              No categories yet. Add a few broad ones — Housing, Food, Transportation — and split them further whenever
              that's useful.
            </p>
          )}
          {categories.data && categories.data.length > 0 && (
            <ul className="divide-y divide-line">
              {categories.data.map((category) => (
                <li key={category.id}>
                  <Row
                    name={category.name}
                    itemCount={category.itemCount}
                    perYearCents={category.perYearCents}
                    indented={category.parentId !== null}
                    onEdit={() => setCategoryForm({ existing: category })}
                  >
                    <span aria-hidden className="size-4 shrink-0 rounded-sm" style={{ backgroundColor: category.color }} />
                    <span className="min-w-0 font-semibold wrap-break-word">{category.name}</span>
                  </Row>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel
          title="Tags"
          about="Any number per item. Use them to build reports, like medical costs or tax-deductible spending."
          addLabel="+ Tag"
          onAdd={() => setTagForm({})}
        >
          {tags.isPending && <p className="text-muted">Loading your tags…</p>}
          {tags.isError && (
            <p role="alert" className="text-danger">
              {tags.error.message}
            </p>
          )}
          {tags.data?.length === 0 && (
            <p className="text-muted">
              No tags yet. A tag cuts across categories — "Reimbursable" or "Vacation 2027" can sit on anything.
            </p>
          )}
          {tags.data && tags.data.length > 0 && (
            <ul className="divide-y divide-line">
              {tags.data.map((tag) => (
                <li key={tag.id}>
                  <Row
                    name={tag.name}
                    itemCount={tag.itemCount}
                    perYearCents={tag.perYearCents}
                    onEdit={() => setTagForm({ existing: tag })}
                  >
                    {/* The user's color exactly as chosen; the text on it switches to stay readable. */}
                    <span
                      className="min-w-0 rounded-2xl px-3 py-1 text-sm font-semibold wrap-break-word"
                      style={{ backgroundColor: tag.color, color: readableTextOn(tag.color) }}
                    >
                      {tag.name}
                    </span>
                  </Row>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      {categoryForm && (
        <CategoryForm existing={categoryForm.existing} categories={categories.data ?? []} onClose={() => setCategoryForm(null)} />
      )}
      {tagForm && <TagForm existing={tagForm.existing} tags={tags.data ?? []} onClose={() => setTagForm(null)} />}
    </>
  );
}

function Panel({
  title,
  about,
  addLabel,
  onAdd,
  children,
}: {
  title: string;
  about: string;
  addLabel: string;
  onAdd: () => void;
  children: ReactNode;
}) {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="rounded-card border border-line bg-surface p-5 md:p-6">
      <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 id={headingId} className="text-lg font-semibold">
            {title}
          </h2>
          <p className="mt-1 text-sm text-muted">{about}</p>
        </div>
        <button type="button" onClick={onAdd} className={`${BUTTON_PRIMARY} shrink-0`}>
          {addLabel}
        </button>
      </div>
      {children}
    </section>
  );
}

/** One category or tag: the whole row is the button that opens it for editing. */
function Row({
  name,
  itemCount,
  perYearCents,
  indented = false,
  onEdit,
  children,
}: {
  name: string;
  itemCount: number;
  perYearCents: number;
  indented?: boolean;
  onEdit: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onEdit}
      aria-label={`Edit ${name}, ${countLabel(itemCount)}, ${formatCents(perYearCents)} a year`}
      // On a phone the name gets the whole first line, so a long one is never cut short.
      className={`flex min-h-11 w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-lg py-2 text-left ${indented ? "pl-7" : ""}`}
    >
      <span className="flex min-w-0 basis-full items-center gap-3 sm:flex-1 sm:basis-0">{children}</span>
      <span className="shrink-0 text-sm text-muted">{countLabel(itemCount)}</span>
      <span data-amount className="ml-auto shrink-0 text-right font-semibold tabular-nums sm:ml-0 sm:w-28">
        {formatCents(perYearCents)}
        <span className="text-xs font-normal text-muted sm:block"> a year</span>
      </span>
    </button>
  );
}

function countLabel(count: number): string {
  return count === 1 ? "1 item" : `${count} items`;
}

function CategoryForm({ existing, categories, onClose }: { existing?: Category; categories: readonly Category[]; onClose: () => void }) {
  const save = useSaveCategory(existing?.id);
  const remove = useDeleteCategory();
  // Newest first, so the colors offered first are the ones used most recently.
  const usedColors = [...categories].reverse().map((category) => category.color);
  // Sub-categories do not nest, so only main categories can hold one — and never itself.
  const parents = categories.filter((category) => category.parentId === null && category.id !== existing?.id);

  return (
    <LabelForm
      noun="category"
      initial={existing ?? { name: "", color: firstUnusedColor(usedColors), parentId: null }}
      usedColors={usedColors}
      parents={parents}
      saving={save}
      onSave={(values) => save.mutate(values, { onSuccess: onClose })}
      deleting={existing && remove}
      onDelete={existing && (() => remove.mutate(existing.id, { onSuccess: onClose }))}
      onClose={onClose}
    />
  );
}

function TagForm({ existing, tags, onClose }: { existing?: Tag; tags: readonly Tag[]; onClose: () => void }) {
  const save = useSaveTag(existing?.id);
  const remove = useDeleteTag();
  const usedColors = [...tags].reverse().map((tag) => tag.color);

  return (
    <LabelForm
      noun="tag"
      initial={existing ? { ...existing, parentId: null } : { name: "", color: firstUnusedColor(usedColors), parentId: null }}
      usedColors={usedColors}
      saving={save}
      onSave={({ name, color }) => save.mutate({ name, color }, { onSuccess: onClose })}
      deleting={existing && remove}
      onDelete={existing && (() => remove.mutate(existing.id, { onSuccess: onClose }))}
      onClose={onClose}
    />
  );
}
