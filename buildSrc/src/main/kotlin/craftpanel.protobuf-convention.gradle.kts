plugins {
    id("com.google.protobuf")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protocVersion = catalog.findVersion("protobuf").get().requiredVersion
val grpcVersion = catalog.findVersion("grpc").get().requiredVersion
val grpcKotlinVersion = catalog.findVersion("grpc-kotlin").get().requiredVersion

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protocVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
                create("grpckt")
            }
            builtins {
                create("kotlin")
            }
        }
    }
}
