# Uses the account's default VPC for demo simplicity.
# For production, replace with a custom VPC with proper public/private subnet separation.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "all" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}
