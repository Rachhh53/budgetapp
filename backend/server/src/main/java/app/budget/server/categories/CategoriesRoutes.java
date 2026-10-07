package app.budget.server.categories;

import app.budget.server.api.ApiException;
import app.budget.server.api.ApiResponse;
import app.budget.server.api.Inputs;
import app.budget.server.categories.CategoriesRepository.Category;
import app.budget.server.categories.TagsRepository.Tag;
import app.budget.server.income.PayCalendar;
import app.budget.server.items.ItemCalendar;
import app.budget.server.items.ItemsRepository;
import app.budget.server.items.ItemsRepository.Item;
import app.budget.server.items.ItemsRepository.Terms;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Categories (one per item, with sub-categories) and tags (any number per item):
 * {@code /api/categories} and {@code /api/tags}.
 */
public final class CategoriesRoutes {

    private static final int NAME_MAX = 60;

    private final CategoriesRepository categories;
    private final TagsRepository tags;
    private final ItemsRepository items;
    private final ItemCalendar itemCalendar;
    private final PayCalendar pay;

    public CategoriesRoutes(
            CategoriesRepository categories, TagsRepository tags, ItemsRepository items, ItemCalendar itemCalendar, PayCalendar pay) {
        this.categories = categories;
        this.tags = tags;
        this.items = items;
        this.itemCalendar = itemCalendar;
        this.pay = pay;
    }

    public void register(JavalinDefaultRoutingApi routes) {
        routes.get("/api/categories", ctx -> ApiResponse.ok(ctx, categoryViews()));
        routes.post("/api/categories", this::createCategory);
        routes.put("/api/categories/{id}", this::updateCategory);
        routes.delete("/api/categories/{id}", this::deleteCategory);
        routes.get("/api/tags", ctx -> ApiResponse.ok(ctx, tagViews()));
        routes.post("/api/tags", this::createTag);
        routes.put("/api/tags/{id}", this::updateTag);
        routes.delete("/api/tags/{id}", this::deleteTag);
    }

    /** Request body for a category. {@code parentId} makes it a sub-category. */
    public record CategoryInput(String name, String color, Long parentId) {}

    public record TagInput(String name, String color) {}

    /**
     * A category with how much it is used.
     *
     * @param itemCount    recurring items filed under it, its sub-categories included
     * @param perYearCents what those items add up to over a year
     */
    public record CategoryView(long id, String name, String color, Long parentId, int itemCount, long perYearCents) {}

    public record TagView(long id, String name, String color, int itemCount, long perYearCents) {}

    public record Deleted(boolean deleted) {}

    /** How many active recurring items use something, and their yearly total. */
    private record Usage(int itemCount, long perYearCents) {
        static final Usage NONE = new Usage(0, 0);

        Usage plus(Usage other) {
            return new Usage(itemCount + other.itemCount, Math.addExact(perYearCents, other.perYearCents));
        }
    }

    private List<CategoryView> categoryViews() {
        List<Category> all = categories.all();
        Map<Long, Usage> usage = new HashMap<>();
        for (Item item : items.all()) {
            Terms terms = item.versionOn(pay.today()).terms();
            if (item.archived() || terms.categoryId() == null) {
                continue;
            }
            Usage one = new Usage(1, itemCalendar.amounts(terms).perYearCents());
            usage.merge(terms.categoryId(), one, Usage::plus);
            // A sub-category's items count towards its parent too.
            all.stream()
                    .filter(category -> category.id() == terms.categoryId() && category.parentId() != null)
                    .forEach(category -> usage.merge(category.parentId(), one, Usage::plus));
        }
        return all.stream().map(category -> view(category, usage.getOrDefault(category.id(), Usage.NONE))).toList();
    }

    private List<TagView> tagViews() {
        Map<Long, Usage> usage = new HashMap<>();
        for (Item item : items.all()) {
            if (item.archived()) {
                continue;
            }
            Usage one = new Usage(1, itemCalendar.amounts(item.versionOn(pay.today()).terms()).perYearCents());
            item.tagIds().forEach(tagId -> usage.merge(tagId, one, Usage::plus));
        }
        return tags.all().stream().map(tag -> view(tag, usage.getOrDefault(tag.id(), Usage.NONE))).toList();
    }

    private void createCategory(Context ctx) {
        CategoryInput input = Inputs.body(ctx, CategoryInput.class);
        String name = Inputs.text(input.name(), "a name for the category", NAME_MAX);
        String color = Inputs.color(input.color());
        requireTopLevelParent(input.parentId());
        if (categories.nameTaken(name, input.parentId(), null)) {
            throw ApiException.conflict("You already have a category called " + name + ".");
        }
        long id = categories.create(name, color, input.parentId());
        ApiResponse.send(ctx, 201, view(categories.find(id).orElseThrow(ApiException::notFound), Usage.NONE));
    }

    private void updateCategory(Context ctx) {
        long id = Inputs.id(ctx, "id");
        CategoryInput input = Inputs.body(ctx, CategoryInput.class);
        String name = Inputs.text(input.name(), "a name for the category", NAME_MAX);
        String color = Inputs.color(input.color());
        if (categories.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        requireTopLevelParent(input.parentId());
        if (input.parentId() != null && (input.parentId() == id || categories.hasChildren(id))) {
            // Nesting stops at two levels, so a category with sub-categories cannot become one.
            throw ApiException.badRequest("A category that has sub-categories can't be moved inside another one.");
        }
        if (categories.nameTaken(name, input.parentId(), id)) {
            throw ApiException.conflict("You already have a category called " + name + ".");
        }
        categories.update(id, name, color, input.parentId());
        ApiResponse.ok(ctx, categoryViews().stream().filter(view -> view.id() == id).findFirst().orElseThrow(ApiException::notFound));
    }

    private void deleteCategory(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (categories.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        if (categories.hasChildren(id)) {
            throw ApiException.conflict("This category still has sub-categories. Move or delete those first.");
        }
        if (categories.isUsed(id)) {
            throw ApiException.conflict("This category is still used by an item. Choose another category for those first.");
        }
        categories.delete(id);
        ApiResponse.ok(ctx, new Deleted(true));
    }

    private void createTag(Context ctx) {
        TagInput input = Inputs.body(ctx, TagInput.class);
        String name = Inputs.text(input.name(), "a name for the tag", NAME_MAX);
        String color = Inputs.color(input.color());
        if (tags.nameTaken(name, null)) {
            throw ApiException.conflict("You already have a tag called " + name + ".");
        }
        long id = tags.create(name, color);
        ApiResponse.send(ctx, 201, view(tags.find(id).orElseThrow(ApiException::notFound), Usage.NONE));
    }

    private void updateTag(Context ctx) {
        long id = Inputs.id(ctx, "id");
        TagInput input = Inputs.body(ctx, TagInput.class);
        String name = Inputs.text(input.name(), "a name for the tag", NAME_MAX);
        String color = Inputs.color(input.color());
        if (tags.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        if (tags.nameTaken(name, id)) {
            throw ApiException.conflict("You already have a tag called " + name + ".");
        }
        tags.update(id, name, color);
        ApiResponse.ok(ctx, tagViews().stream().filter(view -> view.id() == id).findFirst().orElseThrow(ApiException::notFound));
    }

    private void deleteTag(Context ctx) {
        long id = Inputs.id(ctx, "id");
        if (tags.find(id).isEmpty()) {
            throw ApiException.notFound();
        }
        if (tags.isUsed(id)) {
            throw ApiException.conflict("This tag is still on an item. Take it off those first.");
        }
        tags.delete(id);
        ApiResponse.ok(ctx, new Deleted(true));
    }

    /** A parent has to exist and be top-level itself: sub-categories do not nest further. */
    private void requireTopLevelParent(Long parentId) {
        if (parentId == null) {
            return;
        }
        Category parent = categories.find(parentId)
                .orElseThrow(() -> ApiException.badRequest("Please choose the parent category from the list."));
        if (parent.parentId() != null) {
            throw ApiException.badRequest("A sub-category can't have sub-categories of its own.");
        }
    }

    private static CategoryView view(Category category, Usage usage) {
        return new CategoryView(
                category.id(), category.name(), category.color(), category.parentId(), usage.itemCount(), usage.perYearCents());
    }

    private static TagView view(Tag tag, Usage usage) {
        return new TagView(tag.id(), tag.name(), tag.color(), usage.itemCount(), usage.perYearCents());
    }
}
