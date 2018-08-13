<h2 align="center">
    <img src="https://i.imgur.com/h1fInRb.png"/>
    <br>
    :earth_africa: Download, install, manage, debug your IPFS node :earth_africa:
    <br>
    <br>
</h2>

<p align="center">
  <a href="#features">Features</a> ♦
  <a href="#download">Download</a> ♦
  <a href="#screenshots">Screenshots</a> ♦
  <a href="#scripting">Scripting</a>
</p>
<h2></h2>

### Features

- Automatically downloads go-ipfs

- Can connect to an already running IPFS node

- Can start a new IPFS node

- Supports all IPFS actions

- You can create scripts and tasks in Kotlin

- Clear & Minimalist UI

### Download

- [Windows](https://github.com/RHazDev/IPFS-Manager/releases/download/1.0/ipfs-manager-1.0.exe)

- [Linux, MacOS, Freebsd](https://github.com/RHazDev/IPFS-Manager/releases/download/1.0/ipfs-manager-1.0.jar)

### Screenshots
![](https://i.imgur.com/oczGUfE.png)
![](https://i.imgur.com/U2LdSW8.png)
![](https://i.imgur.com/ORE5cjy.png)

### Scripting

Scripts and tasks are written in Kotlin, an awesome programming language for manipulating nullable objects, callbacks, and types.

You can learn it here: http://try.kotlinlang.org/koans

They use [IPFS-API-Kotlin](https://github.com/ligi/ipfs-api-kotlin) for interacting with IPFS

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

            val version = ipfs.info.version() ?: return
            // Request the version. If there is an error, do not continue
            log.append(version.Version)
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

### This app is built with the help of

- [IPFS-Daemon](https://github.com/RHazDev/IPFS-Daemon)

- [IPFS-API-Kotlin](https://github.com/ligi/ipfs-api-kotlin)

- [Kotlin-Compiler-GUI](https://github.com/RHazDev/Kotlin-Compiler-GUI)

- [OpenJFX (JavaFX)](https://wiki.openjdk.java.net/display/OpenJFX/Main)

- [Launch4J](http://launch4j.sourceforge.net/)

- [Go-IPFS](https://github.com/ipfs/go-ipfs)

- [Kotlin](https://kotlinlang.org/)

- [Gradle](https://gradle.org/)

- [IntelliJ IDEA](https://www.jetbrains.com/idea/)