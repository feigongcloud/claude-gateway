package com.vcc.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("upstream_key_secret")
public class UpstreamKeySecretEntity {

    @Id
    @Column("upstream_key_id")
    private String upstreamKeyId;

    @Column("provider")
    private String provider;

    @Column("status")
    private String status;

    @Column("key_version")
    private int keyVersion;

    @Column("iv")
    private byte[] iv;

    @Column("ciphertext")
    private byte[] ciphertext;

    @Column("tag")
    private byte[] tag;

    @Column("aad")
    private String aad;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UpstreamKeySecretEntity() {
    }

    public String getUpstreamKeyId() {
        return upstreamKeyId;
    }

    public void setUpstreamKeyId(String upstreamKeyId) {
        this.upstreamKeyId = upstreamKeyId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public byte[] getIv() {
        return iv;
    }

    public void setIv(byte[] iv) {
        this.iv = iv;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }

    public byte[] getTag() {
        return tag;
    }

    public void setTag(byte[] tag) {
        this.tag = tag;
    }

    public String getAad() {
        return aad;
    }

    public void setAad(String aad) {
        this.aad = aad;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
