package com.gatehousemc.application.admin;

/** The only administrator capabilities exposed by the Gatehouse control plane. */
public enum AdminCapability {
    VIEW,
    DECIDE,
    MANAGE;

    public boolean includes(AdminCapability required) {
        return ordinal() >= required.ordinal();
    }
}
