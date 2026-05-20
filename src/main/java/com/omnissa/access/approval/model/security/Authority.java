package com.omnissa.access.approval.model.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Entity
public class Authority {

    @Id
    @GeneratedValue
    private Long id;

    @NotNull
    @Enumerated(value = EnumType.STRING)
    private AuthorityName authorityName;

    @ManyToMany(mappedBy = "authorities", cascade = CascadeType.ALL)
    private List<UserAccount> userAccounts;

    @JsonIgnore
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AuthorityName getAuthorityName() { return authorityName; }
    public void setAuthorityName(AuthorityName authorityName) { this.authorityName = authorityName; }

    @JsonIgnore
    public List<UserAccount> getUserAccounts() { return userAccounts; }
    public void setUserAccounts(List<UserAccount> userAccounts) { this.userAccounts = userAccounts; }
}
