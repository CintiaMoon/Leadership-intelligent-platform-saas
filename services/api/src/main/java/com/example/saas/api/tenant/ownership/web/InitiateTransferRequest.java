package com.example.saas.api.tenant.ownership.web;

/**
 * The membership of the member who is asked to become the Owner. Not annotated for validation: the controller judges it
 * (missing or malformed is a 400; a membership that is not in the company is a 404 from the service). Unknown fields are
 * rejected while parsing.
 */
record InitiateTransferRequest(String recipientMembershipId) {

}
