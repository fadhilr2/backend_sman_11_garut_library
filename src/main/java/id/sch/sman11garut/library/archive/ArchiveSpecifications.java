package id.sch.sman11garut.library.archive;

import id.sch.sman11garut.library.common.Visibility;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class ArchiveSpecifications {

    private ArchiveSpecifications() {}

    public static Specification<ArchiveItem> withCriteria(ArchiveSearchCriteria criteria, boolean isStaff) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isNull(root.get("deletedAt")));

            if (!isStaff) {
                predicates.add(cb.equal(root.get("visibility"), Visibility.PUBLIC));
            }

            if (criteria == null) {
                return cb.and(predicates.toArray(new Predicate[0]));
            }

            if (criteria.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), criteria.categoryId()));
            }

            if (criteria.mediaType() != null) {
                predicates.add(cb.equal(root.get("mediaType"), criteria.mediaType()));
            }

            if (criteria.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventDate"), criteria.from()));
            }

            if (criteria.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventDate"), criteria.to()));
            }

            if (criteria.q() != null && !criteria.q().isBlank()) {
                String pattern = "%" + criteria.q().trim().toLowerCase() + "%";
                Predicate titleLike = cb.like(cb.lower(root.get("title")), pattern);
                Predicate descLike = cb.like(cb.lower(root.get("description")), pattern);
                predicates.add(cb.or(titleLike, descLike));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
