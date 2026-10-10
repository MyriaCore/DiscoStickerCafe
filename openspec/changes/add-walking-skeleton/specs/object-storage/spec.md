# Spec Delta

## Purpose

Lets the backend store and read objects through the S3 API alone. Moving from local S3-compatible storage to S3 in production is then a configuration change, and storage never accepts unauthenticated access.

## ADDED Requirements

### Requirement: S3 access configured only by properties
The backend SHALL reach object storage only through the S3 API. The endpoint, region, access key, secret key, path-style setting, and bucket name SHALL come from configuration properties, not from code.

#### Scenario: Store and read back an object
- **WHEN** the backend is configured with a storage endpoint, valid credentials, path-style access, and an existing bucket
- **AND** it stores an object under a key
- **THEN** reading that key through the same client returns exactly the stored bytes

### Requirement: Storage rejects unauthenticated access
Object storage SHALL reject requests that carry no credentials or wrong credentials.

#### Scenario: Anonymous read is rejected
- **WHEN** a client with no credentials requests an object from the bucket
- **THEN** storage responds 403

#### Scenario: Wrong secret key is rejected
- **WHEN** a client signs a request with a valid access key and the wrong secret key
- **THEN** storage responds 403

### Requirement: Bucket exists before the backend starts
The local stack SHALL create the configured bucket if it is missing, and the backend SHALL NOT start until that step has succeeded. Running the step again SHALL succeed without changing anything.

#### Scenario: Bucket ready at startup
- **WHEN** the stack starts from empty storage
- **THEN** the bucket-creation step exits 0 before the backend starts

#### Scenario: Repeat run is harmless
- **WHEN** the bucket-creation step runs again against storage where the bucket already exists
- **THEN** it exits 0

### Requirement: Storage creates no buckets implicitly
Object storage SHALL NOT create a bucket as a side effect of writing an object.

#### Scenario: Write to a missing bucket
- **WHEN** a client writes an object to a bucket name that does not exist
- **THEN** storage rejects the write instead of creating the bucket
