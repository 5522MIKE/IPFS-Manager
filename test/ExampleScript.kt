import fr.rhaz.ipfs.KScript
import fr.rhaz.ipfs.append

public class ExampleScript: KScript("script"){

    override fun onEnabled() = log.append("Hello world!")

}