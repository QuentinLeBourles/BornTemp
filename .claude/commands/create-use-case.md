---
description: 'Create use case'
---

Scaffold a new use case end-to-end in the Packmind hexagonal architecture.

## When to Use

- When adding a new business operation to a domain package
- When implementing a new API endpoint that requires orchestration logic
- When a new user action needs validation, authorization, and cross-port coordination

## Context Validation Checkpoints

* [ ] Have you identified which hexagon package the use case belongs to?
* [ ] Do you know the authorization level required (public, member, admin, or space member)?
* [ ] Have you identified which ports and services the use case will coordinate?
* [ ] Is the use case contract name descriptive of the business operation (e.g., `ApplyChangeProposal`, not `UpdateProposal`)?

## Recipe Steps

### Step 1: Define the Use Case Contract

Create the contract in `packages/types/src/{domain}/contracts/I{UseCaseName}UseCase.ts`:

```typescript
import { PackmindCommand } from '@packmind/types';

export type {Name}Command = PackmindCommand & {
  // Add command-specific fields
};

export type {Name}Response = {
  // Define response type
};

export interface I{Name}UseCase {
  execute(command: {Name}Command): Promise<{Name}Response>;
}
```

For space-scoped use cases, extend `SpaceMemberCommand` instead of `PackmindCommand`.

### Step 2: Export Contract from Types Barrel

Add the contract export to `packages/types/src/{domain}/index.ts`.

### Step 3: Implement the Use Case Class

Create the use case in `packages/{domain}/src/application/useCases/{useCaseName}/{useCaseName}.usecase.ts`.

Choose the appropriate base class:
- `AbstractMemberUseCase` — requires organization membership
- `AbstractAdminUseCase` — requires admin role
- `AbstractSpaceMemberUseCase` — requires space membership (use `SpaceMemberCommand` and implement `executeForSpaceMembers`)
- `IPublicUseCase` — no authentication required

Follow the find-validate-delegate pattern:
1. **Find** entities via services or ports
2. **Validate** state (throw `NotFoundError`, etc.)
3. **Delegate** business logic to services

### Step 4: Wire Use Case in Adapter

Add lazy instantiation in the adapter's `initialize()` method and delegate the port method to the use case's `execute()`.

### Step 5: Register in Hexa Facade

Ensure the adapter exposes the new use case through the domain port interface. Update `{Domain}Hexa.ts` if needed.

### Step 6: Create Unit Tests

Create tests in `packages/{domain}/src/application/useCases/{useCaseName}/{useCaseName}.usecase.spec.ts`:

- Test the happy path
- Test each validation error scenario individually
- Test authorization checks
- Use nested `describe` blocks with `beforeEach` for shared setup
- Use test factories from `packages/{domain}/test/` for realistic test data
