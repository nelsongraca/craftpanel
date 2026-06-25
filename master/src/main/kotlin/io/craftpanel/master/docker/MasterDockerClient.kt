package io.craftpanel.master.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient

object MasterDockerClient {

    fun create(endpoint: String): DockerClient {
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(endpoint)
            .build()
        val http = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .build()
        return DockerClientImpl.getInstance(config, http)
    }
}
