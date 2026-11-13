package id.sch.sman11garut.library.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "book_categories")
public class BookCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private BookCategory parent;

    @OneToMany(mappedBy = "parent")
    private List<BookCategory> children = new ArrayList<>();

    public BookCategory() {
    }

    public BookCategory(String name) {
        this.name = name;
    }

    public BookCategory(String name, BookCategory parent) {
        this.name = name;
        this.parent = parent;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BookCategory getParent() {
        return parent;
    }

    public void setParent(BookCategory parent) {
        this.parent = parent;
    }

    public List<BookCategory> getChildren() {
        return children;
    }

    public void setChildren(List<BookCategory> children) {
        this.children = children;
    }

    public Integer getParentId() {
        return parent != null ? parent.getId() : null;
    }
}

