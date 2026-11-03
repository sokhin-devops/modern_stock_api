package com.modernstock.publicsite;

import com.modernstock.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "contact_messages")
public class ContactMessage extends BaseEntity {

    public enum Status { NEW, READ, DONE }

    @Column(nullable = false)
    private String name;
    private String phone;
    private String email;
    @Column(nullable = false)
    private String message;
    @Column(nullable = false)
    private String language = "km";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NEW;
}
