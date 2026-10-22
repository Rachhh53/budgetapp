import { ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import type { CategorySpending, SpendingPart } from "../../api/dashboard";
import { isHexColor } from "../../lib/color";
import { formatCents } from "../../lib/money";
import { Segmented } from "../Segmented";
import { CHART_TOOLTIP } from "../ui";

/** A category's own color; spending with no category is drawn in the muted text color, which follows the theme. */
function colorOf(category: { color: string | null }): string {
  return category.color !== null && isHexColor(category.color) ? category.color : "var(--text-muted)";
}

/** A share of the whole as a whole percent: "43%", or "under 1%" for a sliver that is still something. */
function share(cents: number, totalCents: number): string {
  if (totalCents <= 0) {
    return "0%";
  }
  const percent = Math.round((cents * 100) / totalCents);
  return percent === 0 && cents > 0 ? "under 1%" : `${percent}%`;
}

type Split = "subcategories" | "payees" | "tags";

const SPLIT_LABELS: Record<Split, string> = { subcategories: "Subcategory", payees: "Payee", tags: "Tag" };

/** A slice to draw: a name, an amount and its color. `fillOpacity` tells apart parts that share their category's color. */
interface Slice {
  key: string;
  name: string;
  totalCents: number;
  fill: string;
  fillOpacity: number;
}

/**
 * Spending by category (design/dashboard.png): a ring with the total in the middle, and under it
 * every category with its share and amount. The list is the chart in words and figures: it names
 * each color, so nothing is told by color alone, and it is what a screen reader hears. Each
 * slice takes the color the user gave its category; a thin gap in the card's own color keeps two
 * neighbors of a similar color apart.
 *
 * A slice or a row opens its category: the same ring and list split by subcategory, payee or tag,
 * with All categories to go back (Rachel, 2026-10-20: "looks like you can drill down into it but
 * clicking just draws a box around the section").
 */
export function SpendingDonut({ categories, totalCents }: { categories: readonly CategorySpending[]; totalCents: number }) {
  const [openKey, setOpenKey] = useState<string | null>(null);
  const open = categories.find((category) => keyOf(category) === openKey);
  if (open) {
    return <CategoryParts category={open} totalCents={totalCents} onBack={() => setOpenKey(null)} />;
  }
  const slices: Slice[] = categories
    .filter((category) => category.totalCents > 0)
    .map((category) => ({ key: keyOf(category), name: category.name, totalCents: category.totalCents, fill: colorOf(category), fillOpacity: 1 }));
  return (
    <figure>
      <Ring slices={slices} totalCents={totalCents} centre="Total" onPick={setOpenKey} />
      <figcaption className="mt-4">
        <p className="sr-only">Total {formatCents(totalCents)}</p>
        <ul className="divide-y divide-line">
          {categories.map((category) => (
            <li key={keyOf(category)}>
              <button
                type="button"
                onClick={() => setOpenKey(keyOf(category))}
                aria-label={`Open ${category.name}: ${formatCents(category.totalCents)}`}
                className="-mx-2 flex w-[calc(100%+1rem)] cursor-pointer items-center gap-3 rounded px-2 py-2 text-left hover:bg-bg/60"
              >
                <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: colorOf(category) }} />
                <span className="min-w-0 flex-1 wrap-break-word">{category.name}</span>
                <span className="shrink-0 text-muted tabular-nums">{share(category.totalCents, totalCents)}</span>
                <span data-amount className="w-24 shrink-0 text-right font-semibold tabular-nums">
                  {formatCents(category.totalCents)}
                </span>
                <ChevronRight aria-hidden className="size-4 shrink-0 text-muted" />
              </button>
            </li>
          ))}
        </ul>
      </figcaption>
    </figure>
  );
}

function keyOf(category: CategorySpending): string {
  return category.categoryId === null ? "none" : String(category.categoryId);
}

/** One category opened: its money split by subcategory, payee or tag, whichever it has. */
function CategoryParts({ category, totalCents, onBack }: { category: CategorySpending; totalCents: number; onBack: () => void }) {
  const splits = (["subcategories", "payees", "tags"] as const).filter((split) => category[split].length > 0);
  const [split, setSplit] = useState<Split>(splits[0] ?? "payees");
  const parts = category[split];
  const slices: Slice[] = parts
    .filter((part) => part.totalCents > 0)
    .map((part, index) => partSlice(part, index, category));

  return (
    <figure>
      <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1">
        <button type="button" onClick={onBack} className="-ml-2 flex min-h-11 cursor-pointer items-center gap-1 rounded px-2 font-medium hover:bg-bg">
          <ChevronLeft aria-hidden className="size-4" />
          All categories
        </button>
        <span className="flex min-w-0 items-center gap-2 font-semibold">
          <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: colorOf(category) }} />
          <span className="min-w-0 wrap-break-word">{category.name}</span>
        </span>
        <span className="text-muted">
          <span data-amount>{formatCents(category.totalCents)}</span> · {share(category.totalCents, totalCents)} of all spending
        </span>
      </div>
      {splits.length > 1 && (
        <div className="mb-3">
          <Segmented
            legend="Split by"
            options={splits.map((value) => ({ value, label: SPLIT_LABELS[value] }))}
            value={split}
            onChange={setSplit}
            dense
          />
        </div>
      )}
      <Ring slices={slices} totalCents={category.totalCents} centre={category.name} />
      <figcaption className="mt-4">
        <p className="sr-only">
          {category.name}, {formatCents(category.totalCents)}, by {SPLIT_LABELS[split].toLowerCase()}
        </p>
        <ul className="divide-y divide-line">
          {parts.map((part, index) => {
            const slice = partSlice(part, index, category);
            return (
              <li key={slice.key} className="flex items-center gap-3 py-2">
                <span aria-hidden className="size-3 shrink-0 rounded-sm" style={{ backgroundColor: slice.fill, opacity: slice.fillOpacity }} />
                <span className="min-w-0 flex-1 wrap-break-word">{part.name}</span>
                <span className="shrink-0 text-muted tabular-nums">{share(part.totalCents, category.totalCents)}</span>
                <span data-amount className="w-24 shrink-0 text-right font-semibold tabular-nums">
                  {formatCents(part.totalCents)}
                </span>
              </li>
            );
          })}
        </ul>
        {split === "tags" && <p className="mt-2 text-sm text-muted">Money with two tags counts under each, so these can add up to more.</p>}
      </figcaption>
    </figure>
  );
}

/**
 * A part's slice: its own color where it has one (a subcategory or a tag), else its category's
 * color, lighter the further down the list, so neighbors still tell apart; the list names each.
 */
function partSlice(part: SpendingPart, index: number, category: CategorySpending): Slice {
  const own = part.color !== null && isHexColor(part.color);
  return {
    key: `${part.id ?? "none"}-${part.name}`,
    name: part.name,
    totalCents: part.totalCents,
    fill: own ? part.color! : colorOf(category),
    fillOpacity: own ? 1 : Math.max(0.3, 1 - index * 0.15),
  };
}

/** The ring itself, with an amount in the middle. A slice opens what it stands for when `onPick` is given. */
function Ring({ slices, totalCents, centre, onPick }: { slices: readonly Slice[]; totalCents: number; centre: string; onPick?: (key: string) => void }) {
  return (
    // The list below says everything the ring does, so the ring is hidden from screen readers. A slice
    // shows no focus box: the list's rows are what the keyboard opens.
    <div data-amount aria-hidden className="relative mx-auto h-56 w-full max-w-64 [&_*:focus]:outline-none">
      <ResponsiveContainer width="100%" height="100%">
        <PieChart accessibilityLayer={false}>
          <Pie
            data={slices.map((slice) => ({ ...slice }))}
            dataKey="totalCents"
            nameKey="name"
            innerRadius="62%"
            outerRadius="100%"
            startAngle={90}
            endAngle={-270}
            stroke="var(--surface)"
            strokeWidth={2}
            isAnimationActive={false}
            onClick={onPick ? (_, index) => onPick(slices[index]!.key) : undefined}
            className={onPick ? "cursor-pointer" : undefined}
          >
            {slices.map((slice) => (
              <Cell key={slice.key} fill={slice.fill} fillOpacity={slice.fillOpacity} />
            ))}
          </Pie>
          {/* Above the total in the middle, which comes later on the page and otherwise covers it (Rachel, 2026-10-21). */}
          <Tooltip
            formatter={(value, name) => [`${formatCents(Number(value))} · ${share(Number(value), totalCents)}`, name]}
            {...CHART_TOOLTIP}
            wrapperStyle={{ zIndex: 10 }}
          />
        </PieChart>
      </ResponsiveContainer>
      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center px-12 text-center">
        <span className="max-w-full truncate text-sm text-muted">{centre}</span>
        <span className="text-xl font-bold tabular-nums">{formatCents(totalCents)}</span>
      </div>
    </div>
  );
}
