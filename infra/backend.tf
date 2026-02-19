# Remote state — create the bucket and lock table BEFORE running terraform init.
# See README.md "AWS Prerequisites" for the one-time setup commands.
terraform {
  backend "s3" {
    # Replace ACCOUNT_ID with your 12-digit AWS account ID
    bucket         = "orderflow-terraform-state-ACCOUNT_ID"
    key            = "orderflow/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "orderflow-terraform-locks"
    encrypt        = true
  }
}
