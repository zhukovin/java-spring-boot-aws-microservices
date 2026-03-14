output "table_names" {
  description = "All DynamoDB table names"
  value = {
    orders                  = aws_dynamodb_table.orders.name
    order_idempotency_keys  = aws_dynamodb_table.order_idempotency_keys.name
    order_outbox            = aws_dynamodb_table.order_outbox.name
    inventory               = aws_dynamodb_table.inventory.name
    inventory_idempotency   = aws_dynamodb_table.inventory_idempotency.name
  }
}
