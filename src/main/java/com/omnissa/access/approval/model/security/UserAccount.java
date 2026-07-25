package com.omnissa.access.approval.model.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Entity
public class UserAccount implements UserDetails {

    @Id
    @GeneratedValue
    private Long id;

    @Column(unique = true)
    @NotNull
    @Size(min = 4, max = 50)
    private String username;

    @NotNull
    @Size(min = 4, max = 100)
    private String password;

    @NotNull
    @Size(max = 100)
    private String firstName;

    @NotNull
    @Size(max = 100)
    private String lastName;

    @NotNull
    @Size(max = 100)
    private String email;

    private Boolean enabled;

    @ManyToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinTable(name = "USER_AUTHORITY")
    private List<Authority> authorities;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public void setAuthorities(List<Authority> authorities) { this.authorities = authorities; }

    /**
     * The persisted {@link Authority} rows. Named distinctly from
     * {@link #getAuthorities()} so the {@link UserDetails} contract stays
     * unambiguous, and marked {@code @JsonIgnore} so no serializer can walk
     * back into the entity graph.
     */
    @JsonIgnore
    public List<Authority> getAuthorityEntities() { return authorities; }

    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList(authZ(authorities));
    }

    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }

    /** Null-safe: a user with no authority rows gets an empty authority list, not an NPE. */
    private String[] authZ(List<Authority> authorities) {
        if (authorities == null) {
            return new String[0];
        }
        return authorities.stream()
                .filter(a -> a != null && a.getAuthorityName() != null)
                .map(a -> a.getAuthorityName().toString())
                .toArray(String[]::new);
    }
}
