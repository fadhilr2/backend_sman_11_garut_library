package id.sch.sman11garut.library.category;

import id.sch.sman11garut.library.common.AppException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final BookCategoryRepository bookCategoryRepository;
    private final ArchiveCategoryRepository archiveCategoryRepository;

    public CategoryService(
            BookCategoryRepository bookCategoryRepository,
            ArchiveCategoryRepository archiveCategoryRepository) {
        this.bookCategoryRepository = bookCategoryRepository;
        this.archiveCategoryRepository = archiveCategoryRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories(CategoryType type) {
        CategoryType resolved = type != null ? type : CategoryType.BOOK;
        if (resolved == CategoryType.BOOK) {
            return bookCategoryRepository.findAllByOrderByNameAsc()
                    .stream()
                    .map(CategoryDto::from)
                    .toList();
        } else {
            return archiveCategoryRepository.findAllByOrderByNameAsc()
                    .stream()
                    .map(CategoryDto::from)
                    .toList();
        }
    }

    @Transactional(readOnly = true)
    public BookCategory getBookCategoryEntity(Integer id) {
        if (id == null) {
            return null;
        }
        return bookCategoryRepository.findById(id)
                .orElseThrow(() -> AppException.notFound("Book category not found with id: " + id));
    }

    @Transactional
    public CategoryDto createCategory(CreateCategoryRequest request) {
        CategoryType type = request.resolveType();
        String name = request.name().trim();

        if (type == CategoryType.BOOK) {
            if (bookCategoryRepository.existsByName(name)) {
                throw AppException.conflict("DUPLICATE_CATEGORY", "Book category '" + name + "' already exists");
            }
            BookCategory parent = null;
            if (request.parentId() != null) {
                parent = bookCategoryRepository.findById(request.parentId())
                        .orElseThrow(() -> AppException.notFound("Parent category not found with id: " + request.parentId()));
            }
            BookCategory category = new BookCategory(name, parent);
            return CategoryDto.from(bookCategoryRepository.save(category));
        } else {
            if (archiveCategoryRepository.existsByName(name)) {
                throw AppException.conflict("DUPLICATE_CATEGORY", "Archive category '" + name + "' already exists");
            }
            ArchiveCategory parent = null;
            if (request.parentId() != null) {
                parent = archiveCategoryRepository.findById(request.parentId())
                        .orElseThrow(() -> AppException.notFound("Parent category not found with id: " + request.parentId()));
            }
            ArchiveCategory category = new ArchiveCategory(name, parent);
            return CategoryDto.from(archiveCategoryRepository.save(category));
        }
    }

    @Transactional
    public CategoryDto updateCategory(Integer id, UpdateCategoryRequest request, CategoryType type) {
        CategoryType resolved = type != null ? type : CategoryType.BOOK;
        String name = request.name().trim();

        if (request.parentId() != null && request.parentId().equals(id)) {
            throw AppException.badRequest("INVALID_PARENT", "Category cannot be its own parent");
        }

        if (resolved == CategoryType.BOOK) {
            BookCategory category = bookCategoryRepository.findById(id)
                    .orElseThrow(() -> AppException.notFound("Book category not found with id: " + id));

            if (bookCategoryRepository.existsByNameAndIdNot(name, id)) {
                throw AppException.conflict("DUPLICATE_CATEGORY", "Book category '" + name + "' already exists");
            }

            category.setName(name);
            if (request.parentId() != null) {
                BookCategory parent = bookCategoryRepository.findById(request.parentId())
                        .orElseThrow(() -> AppException.notFound("Parent category not found with id: " + request.parentId()));
                category.setParent(parent);
            } else {
                category.setParent(null);
            }
            return CategoryDto.from(bookCategoryRepository.save(category));
        } else {
            ArchiveCategory category = archiveCategoryRepository.findById(id)
                    .orElseThrow(() -> AppException.notFound("Archive category not found with id: " + id));

            if (archiveCategoryRepository.existsByNameAndIdNot(name, id)) {
                throw AppException.conflict("DUPLICATE_CATEGORY", "Archive category '" + name + "' already exists");
            }

            category.setName(name);
            if (request.parentId() != null) {
                ArchiveCategory parent = archiveCategoryRepository.findById(request.parentId())
                        .orElseThrow(() -> AppException.notFound("Parent category not found with id: " + request.parentId()));
                category.setParent(parent);
            } else {
                category.setParent(null);
            }
            return CategoryDto.from(archiveCategoryRepository.save(category));
        }
    }

    @Transactional
    public void deleteCategory(Integer id, CategoryType type) {
        CategoryType resolved = type != null ? type : CategoryType.BOOK;
        if (resolved == CategoryType.BOOK) {
            BookCategory category = bookCategoryRepository.findById(id)
                    .orElseThrow(() -> AppException.notFound("Book category not found with id: " + id));

            if (bookCategoryRepository.countActiveBooks(id) > 0) {
                throw AppException.conflict("CATEGORY_IN_USE", "Cannot delete category because it is currently assigned to existing books");
            }

            bookCategoryRepository.delete(category);
        } else {
            ArchiveCategory category = archiveCategoryRepository.findById(id)
                    .orElseThrow(() -> AppException.notFound("Archive category not found with id: " + id));

            archiveCategoryRepository.delete(category);
        }
    }
}

