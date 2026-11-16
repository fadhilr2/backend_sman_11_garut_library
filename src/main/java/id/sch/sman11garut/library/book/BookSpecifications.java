package id.sch.sman11garut.library.book;

import id.sch.sman11garut.library.common.Visibility;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class BookSpecifications {

    private BookSpecifications() {
    }

    public static Specification<Book> withCriteria(BookSearchCriteria criteria, boolean isStaff) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Filter out soft-deleted books
            predicates.add(cb.isNull(root.get("deletedAt")));

            // 2. Staff see everything; anonymous users only see PUBLIC
            if (!isStaff) {
                predicates.add(cb.equal(root.get("visibility"), Visibility.PUBLIC));
            }

            if (criteria == null) {
                return cb.and(predicates.toArray(new Predicate[0]));
            }

            // 3. Category filter
            if (criteria.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), criteria.categoryId()));
            }

            // 4. Grade filter
            if (criteria.grade() != null) {
                predicates.add(cb.equal(root.get("gradeLevel"), criteria.grade()));
            }

            // 5. Book type filter
            if (criteria.type() != null) {
                predicates.add(cb.equal(root.get("bookType"), criteria.type()));
            }

            // 6. Text query LIKE search
            if (criteria.q() != null && !criteria.q().isBlank()) {
                String pattern = "%" + criteria.q().trim().toLowerCase() + "%";
                Predicate titleLike = cb.like(cb.lower(root.get("title")), pattern);
                Predicate authorLike = cb.like(cb.lower(root.get("author")), pattern);
                Predicate descLike = cb.like(cb.lower(root.get("description")), pattern);
                predicates.add(cb.or(titleLike, authorLike, descLike));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

