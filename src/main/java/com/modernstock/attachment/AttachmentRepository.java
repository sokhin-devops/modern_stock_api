package com.modernstock.attachment;

import java.util.Optional;

import com.modernstock.common.TenantRepository;

public interface AttachmentRepository extends TenantRepository<Attachment> {

    Optional<Attachment> findByDocTypeAndDocId(String docType, Long docId);
}
