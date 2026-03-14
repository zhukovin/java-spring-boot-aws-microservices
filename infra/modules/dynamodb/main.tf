# ── orders ────────────────────────────────────────────────────────────────────
# PK = "ORDER#<orderId>"  SK = "METADATA"
# GSI_CustomerOrders: PK = customerId, SK = createdAt

resource "aws_dynamodb_table" "orders" {
  name         = "orders"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }
  attribute {
    name = "customerId"
    type = "S"
  }
  attribute {
    name = "createdAt"
    type = "S"
  }

  global_secondary_index {
    name            = "GSI_CustomerOrders"
    hash_key        = "customerId"
    range_key       = "createdAt"
    projection_type = "ALL"
  }

  server_side_encryption {
    enabled = true
  }

  point_in_time_recovery {
    enabled = true
  }
}

# ── order_idempotency_keys ────────────────────────────────────────────────────
# PK = "IDEMPOTENCY#<idempotencyKey>"
# TTL field: ttl (epoch seconds, 24 h expiry)

resource "aws_dynamodb_table" "order_idempotency_keys" {
  name         = "order_idempotency_keys"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"

  attribute {
    name = "PK"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  server_side_encryption {
    enabled = true
  }
}

# ── order_outbox ───────────────────────────────────────────────────────────────
# PK = "OUTBOX#<id>"
# GSI_PendingOutbox: PK = status, SK = createdAt

resource "aws_dynamodb_table" "order_outbox" {
  name         = "order_outbox"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"

  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "status"
    type = "S"
  }
  attribute {
    name = "createdAt"
    type = "S"
  }

  global_secondary_index {
    name            = "GSI_PendingOutbox"
    hash_key        = "status"
    range_key       = "createdAt"
    projection_type = "ALL"
  }

  server_side_encryption {
    enabled = true
  }
}

# ── inventory ─────────────────────────────────────────────────────────────────
# PK = "ITEM#<skuId>"

resource "aws_dynamodb_table" "inventory" {
  name         = "inventory"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"

  attribute {
    name = "PK"
    type = "S"
  }

  server_side_encryption {
    enabled = true
  }
}

# ── inventory_idempotency ─────────────────────────────────────────────────────
# PK = "PROCESSED#<orderId>"
# TTL field: ttl (epoch seconds, 48 h expiry)

resource "aws_dynamodb_table" "inventory_idempotency" {
  name         = "inventory_idempotency"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"

  attribute {
    name = "PK"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  server_side_encryption {
    enabled = true
  }
}
