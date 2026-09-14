# wiggly-gin

A multiplayer game server for the Gin Rummy game.

# Language

This game server will be written in Scala 3

It will use the Typelevel ecosystem for most libraries and effects.

# Paradigm

Code is pure functional.

It should represent business logic in pure code that does not rely on IO or concrete effects until necessary.

# Code structure

This code uses Hexagonal/Ports & Adapters architectural pattern to separate business code from infrastructure.

# Testing

This code uses TDD as a development pattern.

Most tests should be unit tests that run in pure code.

# Deployment

The application should be designed as a 12-factor app to be built as a container.
