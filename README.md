<h3 align="center">
    <img src="https://i.imgur.com/h1fInRb.png"/>
    <br>
    :earth_africa: Download, install, manage, debug your IPFS node :earth_africa:
    <br>
    <br>
    <img src="https://i.imgur.com/CeBlyxM.png"/>
</h3>

<hr>
<h3 align="center">
  <a href="#download">Download</a> •
  <a href="#features">Features</a> •
  <a href="#screenshots">Screenshots</a> •
  <a href="#videos">Videos</a> •
  <a href="#scripting">Scripting</a> •
  <a href="#join-us">Join us!</a>
</h3>
<hr>

## Download

You need [Java](https://java.com/download/) (It works with Java 8, Java 9 and Java 10)

- [Windows](https://github.com/RHazDev/IPFS-Manager/raw/master/exe/ipfs-manager-1.2.16.exe)

- [Linux, MacOS, Freebsd](https://github.com/RHazDev/IPFS-Manager/raw/master/jar/ipfs-manager-1.2.16.jar)

## Features

- It can start a new IPFS node or connect to an already running IPFS node

- You can use multiple IPFS repositories and manage them without actually start them

- It supports all IPFS actions with Command-Line or Graphical interfaces

- You can modify your node configuration

- You can create scripts and tasks in Kotlin

- You can drag and drop files and hashes

- You can manage keys and pins

- It generates QR codes

- It has a Clear & Minimalist design

## Screenshots
![](https://i.imgur.com/AJ0Ge3b.png)
![](https://i.imgur.com/U2LdSW8.png)
![](https://i.imgur.com/SV6aHm3.png)

## Videos

[Roscoe's IPFS: Creating multiple keys](https://www.youtube.com/watch?v=BgVMhwaLg_E)

[Roscoe's IPFS: Connect and share with IPFS-Manager 1.2](https://www.youtube.com/watch?v=CCiGgnRSAN0)

[IPFS-Manager #1 : Compiling & Running a script
](https://youtu.be/A1ljWpe_CS0)

## Scripting

Scripts and tasks are written in Kotlin, an awesome programming language for manipulating nullable objects, callbacks, and types.

You can learn it here: http://try.kotlinlang.org/koans

They use [Java-IPFS-API](https://github.com/ipfs/java-ipfs-api) for interacting with IPFS

You can compile them easily with [Kotlin-Compiler-GUI](https://github.com/RHazDev/Kotlin-Compiler-GUI)

Here is a video: [IPFS-Manager #1 : Compiling & Running a script
](https://youtu.be/A1ljWpe_CS0)

##### Scripts

A script is a simple class that is enabled when the manager is connected

    import fr.rhaz.ipfs.KScript
    import fr.rhaz.ipfs.append

    public class ExampleScript: KScript("script"){

        override fun onEnabled() = log.append("Hello world!")

    }

This script will write "Hello world!" to the console

##### Tasks

A task is a script that can be run when you type its name in the console

You can use tasks to automate actions like adding files, retrieve domains, publish IPNS, ...

    import fr.rhaz.ipfs.Task
    import fr.rhaz.ipfs.append

    public class ExampleTask: Task("example"){

        override fun onEnabled() = log.append("ExampleTask enabled!")

        override fun onCall(line: String){

            val version = ipfs.version() ?: return
            // Request the version. If there is an error, do not continue
            log.append(version)
            // Write the version

            val args = line.split(" ")
            // Split the line in multiple arguments
            if(args.size > 1) log.append(args[1])
            // If there is at least two arguments (the command + the first argument)
            // then write the first argument
        }
    }

This task writes "ExampleTask enabled!" in the console when the manager is connected

When you type "example" (its name), it writes the IPFS version

When you type "example anything" ("anything" can be anything), it will write the IPFS version followed by what you wrote

## This app is built with the help of

- [IPFS-Daemon](https://github.com/RHazDev/IPFS-Daemon)

- [Java-IPFS-API](https://github.com/ipfs/java-ipfs-api)

- [Kotlin-Compiler-GUI](https://github.com/RHazDev/Kotlin-Compiler-GUI)

- [OpenJFX (JavaFX)](https://wiki.openjdk.java.net/display/OpenJFX/Main)

- [Launch4J](http://launch4j.sourceforge.net/)

- [Go-IPFS](https://github.com/ipfs/go-ipfs)

- [Kotlin](https://kotlinlang.org/)

- [Gradle](https://gradle.org/)

- [IntelliJ IDEA](https://www.jetbrains.com/idea/)

## Join us!

<h2 align="center">
    <a href="https://steemit.com/ipfs/@hazae41/sweet-ipfs-full-ipfs-node-for-android"><img src="https://steemitimages.com/DQmUGojp38u9sSMvPhXTqHGC1TqbG9ixDAb6gAKSdRhMuaR/steemit%20(1).png"/></a>
    <a href="https://discord.gg/ttVYe8S"><img src="https://i.imgur.com/aT9BaNV.png"/></a>
</h2>