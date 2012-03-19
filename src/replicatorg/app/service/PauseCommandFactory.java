// vim:cindent:cino=\:0:et:fenc=utf-8:ff=unix:sw=4:ts=4:

package replicatorg.app.service;

import java.util.Arrays;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class PauseCommandFactory extends RemoteCommandFactory
{
    public boolean isMatch(final String commandName)
    {
        final boolean result = "pause".equals(commandName);
        return result;
    }

    @Override
    protected Command createCommand(final CommandLine commandLine)
        throws MissingArgumentException, ExtraArgumentsException
    {
        final List<String> buildArguments = getArgumentsAsList(commandLine);
        if (0 != buildArguments.size())
        {
            throw new ExtraArgumentsException("pause", buildArguments);
        }
        else
        {
            final String busName = handleBusName(commandLine);
            final Command command = new PauseCommand(busName);
            return command;
        }
    }
}