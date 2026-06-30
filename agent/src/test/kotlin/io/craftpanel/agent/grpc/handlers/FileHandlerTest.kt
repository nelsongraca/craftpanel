package io.craftpanel.agent.grpc.handlers

import io.craftpanel.proto.ErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

class FileHandlerTest : FunSpec({
    test("classifyFileError maps NoSuchFileException to NOT_FOUND") {
        classifyFileError(NoSuchFileException("/path")) shouldBe ErrorCode.NOT_FOUND
    }

    test("classifyFileError maps FileAlreadyExistsException to ALREADY_EXISTS") {
        classifyFileError(FileAlreadyExistsException("/path")) shouldBe ErrorCode.ALREADY_EXISTS
    }

    test("classifyFileError maps DirectoryNotEmptyException to CONFLICT") {
        classifyFileError(DirectoryNotEmptyException("/path")) shouldBe ErrorCode.CONFLICT
    }

    test("classifyFileError maps AccessDeniedException to PERMISSION_DENIED") {
        classifyFileError(AccessDeniedException("/path")) shouldBe ErrorCode.PERMISSION_DENIED
    }

    test("classifyFileError maps IOException to INTERNAL") {
        classifyFileError(IOException("disk error")) shouldBe ErrorCode.INTERNAL
    }

    test("classifyFileError maps generic exception to INTERNAL") {
        classifyFileError(RuntimeException("something broke")) shouldBe ErrorCode.INTERNAL
    }

    test("classifyFileError maps IllegalArgumentException to INTERNAL") {
        classifyFileError(IllegalArgumentException("bad arg")) shouldBe ErrorCode.INTERNAL
    }
})