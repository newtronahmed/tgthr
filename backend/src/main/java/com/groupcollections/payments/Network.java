package com.groupcollections.payments;

/** Ghanaian mobile money networks, mapped to the partner's provider codes. */
public enum Network {
  MTN("mtn"),
  TELECEL("vod"),
  AT("atl");

  private final String providerCode;

  Network(String providerCode) {
    this.providerCode = providerCode;
  }

  public String providerCode() {
    return providerCode;
  }

  /** Paystack transfer-recipient bank code (uppercase). */
  public String transferCode() {
    return switch (this) {
      case MTN -> "MTN";
      case TELECEL -> "VOD";
      case AT -> "ATL";
    };
  }
}
