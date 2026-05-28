package io.craftpanel.master.service

sealed class ServiceException(message: String) : Exception(message)
class NotFoundException(message: String) : ServiceException(message)
class ForbiddenException(message: String) : ServiceException(message)
class ConflictException(message: String) : ServiceException(message)
class UnprocessableException(message: String) : ServiceException(message)
class BadGatewayException(message: String) : ServiceException(message)
class BadRequestException(message: String) : ServiceException(message)
