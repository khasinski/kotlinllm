# Contributing to KotlinLLM

Thank you for your interest in contributing to KotlinLLM! This document provides guidelines and information for contributors.

## Code of Conduct

Please be respectful and considerate in all interactions. We welcome contributors of all experience levels.

## Getting Started

### Prerequisites

- JDK 21 or later
- Gradle 8.x (wrapper included)

### Setup

1. Fork the repository
2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/kotlinllm.git
   cd kotlinllm
   ```
3. Build the project:
   ```bash
   ./gradlew build
   ```
4. Run tests:
   ```bash
   ./gradlew test
   ```

### Project Structure

```
kotlinllm/
├── kotlinllm-core/           # Core functionality
│   ├── core/                 # Chat, Message, Tool, Provider
│   ├── providers/            # OpenAI, Anthropic, Ollama
│   ├── memory/               # Memory abstractions
│   └── structured/           # Structured output
├── kotlinllm-resilience/     # Rate limiting, circuit breaker
├── kotlinllm-observability/  # Metrics, logging
├── kotlinllm-documents/      # Document loaders
├── kotlinllm-jpa/            # JPA persistence
└── kotlinllm-exposed/        # Exposed persistence
```

## Development Guidelines

### Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Keep functions focused and small
- Add KDoc comments for public APIs

### Testing

- Write tests for new features
- Maintain existing test coverage
- Use MockWebServer for provider tests (no real API calls in CI)
- Run tests before submitting PR: `./gradlew test`

### Commits

- Use clear, descriptive commit messages
- Reference issues when applicable: `Fix #123: Add retry logic`
- Keep commits focused on single changes

## How to Contribute

### Reporting Bugs

1. Check existing issues first
2. Create a new issue with:
   - Clear title
   - Steps to reproduce
   - Expected vs actual behavior
   - KotlinLLM version
   - Kotlin/JDK version

### Suggesting Features

1. Check existing issues and discussions
2. Create an issue describing:
   - The use case
   - Proposed solution
   - Alternatives considered

### Submitting Pull Requests

1. Create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes

3. Add tests for new functionality

4. Ensure all tests pass:
   ```bash
   ./gradlew test
   ```

5. Commit your changes

6. Push to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

7. Create a Pull Request with:
   - Clear title
   - Description of changes
   - Link to related issues
   - Screenshots/examples if applicable

### PR Review Process

1. Maintainers will review your PR
2. Address any feedback
3. Once approved, your PR will be merged

## Areas for Contribution

### Good First Issues

Look for issues labeled `good first issue` - these are great for newcomers.

### High-Impact Areas

- **New Providers**: Google Gemini, Cohere, Mistral
- **Document Loaders**: DOCX, CSV, JSON
- **Memory Strategies**: Vector-based, hybrid
- **Performance**: Benchmarks, optimizations
- **Documentation**: Examples, tutorials

## Running Specific Tests

```bash
# All tests
./gradlew test

# Single module
./gradlew :kotlinllm-core:test

# Single test class
./gradlew :kotlinllm-core:test --tests "com.kotlinllm.providers.OpenAIProviderIntegrationTest"

# With console output
./gradlew test --info
```

## Building Documentation

```bash
# Generate KDoc
./gradlew dokkaHtml

# Output in build/dokka/html
```

## Release Process (Maintainers)

1. Update version in `build.gradle.kts`
2. Update `CHANGELOG.md`
3. Create and push tag:
   ```bash
   git tag v0.9.0
   git push origin v0.9.0
   ```
4. GitHub Actions will build and publish

## Questions?

- Open a GitHub Discussion
- Check existing documentation

Thank you for contributing!
