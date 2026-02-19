locals {
  services = ["order-service", "inventory-service", "payment-service", "api-gateway"]
}

resource "aws_ecr_repository" "services" {
  for_each             = toset(local.services)
  name                 = "orderflow/${each.key}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Keep the 10 most recent images per repo; expire everything else automatically
resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = aws_ecr_repository.services
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
