package com.modernstock.attachment;

import com.modernstock.common.TenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** The photo or scan kept with one document (PLAIN.md 11.1 rule 4). */
@Getter
@Setter
@Entity
@Table(name = "attachments")
public class Attachment extends TenantEntity {

    /** The document's API path, for example "goods-receipts". */
    @Column(nullable = false)
    private String docType;
    @Column(nullable = false)
    private Long docId;
    /** FileStorage name in the private "documents" folder. */
    @Column(nullable = false)
    private String path;
}
