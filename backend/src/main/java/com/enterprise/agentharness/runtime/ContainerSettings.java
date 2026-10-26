package com.enterprise.agentharness.runtime;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Container runtime configuration shared by every module that starts containers (QA runs, the code agent, backup
 * drills). Property names keep their original {@code eahp.qa.*} prefix so existing deployments need no change.
 * Containers mount the whole work directory at the same path as this process sees it:
 * from a named Docker volume when {@code eahp.qa.volume} is set (portable, works with Docker Desktop and remote
 * daemons), otherwise by bind-mounting the work directory, which must then have the same path on the Docker host.
 */
@Component
public class ContainerSettings {
    private final Path workdir;
    private final String docker;
    private final String git;
    private final String containerUser;
    private final String cpus;
    private final String memory;
    private final String dockerSocket;
    private final String volume;

    public ContainerSettings(@Value("${eahp.qa.workdir:${java.io.tmpdir}/eahp-qa}") String workdir,
            @Value("${eahp.qa.docker:docker}") String docker, @Value("${eahp.qa.git:git}") String git,
            @Value("${eahp.qa.container-user:}") String containerUser, @Value("${eahp.qa.cpus:2}") String cpus,
            @Value("${eahp.qa.memory:4g}") String memory, @Value("${eahp.qa.docker-socket:/var/run/docker.sock}") String dockerSocket,
            @Value("${eahp.qa.volume:}") String volume) {
        this.workdir = Path.of(workdir).toAbsolutePath().normalize();
        this.docker = docker; this.git = git; this.containerUser = containerUser;
        this.cpus = cpus; this.memory = memory; this.dockerSocket = dockerSocket; this.volume = volume;
    }

    public Path workdir() { return workdir; }
    public String docker() { return docker; }
    public String git() { return git; }
    /** Empty means "same uid:gid as this process", so the runner never leaves files this process cannot delete. */
    public String containerUser() { return containerUser; }
    public String cpus() { return cpus; }
    public String memory() { return memory; }
    public String dockerSocket() { return dockerSocket; }
    /** The Docker mount source for the work directory: a named volume, or the work directory itself. */
    public String workdirMountSource() { return volume.isBlank() ? workdir.toString() : volume; }
}
