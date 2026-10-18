package com.example.learnhub.course.api;

import com.example.learnhub.course.application.CategoryService;
import com.example.learnhub.course.domain.Category;
import com.example.learnhub.course.infrastructure.ConcurrencyControl;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/categories}: listing is public; create/rename/delete require the ADMINISTRATOR role,
 * enforced by the gateway's {@code categories-admin} route (research.md R5).
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    public record CategoryRequest(String name) {
    }

    public record CategoryView(UUID id, String name, long version) {
        static CategoryView of(Category category) {
            return new CategoryView(category.getId(), category.getName(), category.getVersion());
        }
    }

    @GetMapping
    public List<CategoryView> list() {
        return categoryService.list().stream().map(CategoryView::of).toList();
    }

    @PostMapping
    public ResponseEntity<CategoryView> create(@RequestBody CategoryRequest request) {
        CategoryView view = CategoryView.of(categoryService.create(request.name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, ConcurrencyControl.etagOf(view.version()))
                .body(view);
    }

    @PatchMapping("/{categoryId}")
    public ResponseEntity<CategoryView> rename(@PathVariable UUID categoryId, @RequestBody CategoryRequest request,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        CategoryView view = CategoryView.of(categoryService.rename(categoryId, request.name(), ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ConcurrencyControl.etagOf(view.version()))
                .body(view);
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID categoryId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
        categoryService.delete(categoryId, ifMatch);
    }
}
