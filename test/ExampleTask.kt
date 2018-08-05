import fr.rhaz.ipfs.Task
import fr.rhaz.ipfs.append

public class ExampleTask: Task("example"){

    override fun onEnabled() = log.append("ExampleTask enabled!")

    override fun onCall(line: String){

        val version = ipfs.info.version() ?: return
        log.append(version.Version)

        val args = line.split(" ")
        if(args.size > 1) log.append(args[1])
    }
}