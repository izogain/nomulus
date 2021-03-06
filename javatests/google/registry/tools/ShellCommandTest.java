// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.MissingCommandException;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.FakeClock;
import google.registry.tools.ShellCommand.JCommanderCompletor;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ShellCommandTest {

  CommandRunner cli = mock(CommandRunner.class);
  FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  public ShellCommandTest() {}

  @Test
  public void testParsing() {
    assertThat(ShellCommand.parseCommand("foo bar 123 baz+ // comment \"string data\""))
        .isEqualTo(new String[] {"foo", "bar", "123", "baz+", "//", "comment", "string data"});
    assertThat(ShellCommand.parseCommand("\"got \\\" escapes?\""))
        .isEqualTo(new String[] {"got \" escapes?"});
    assertThat(ShellCommand.parseCommand("")).isEqualTo(new String[0]);
  }

  private ShellCommand createShellCommand(
      CommandRunner commandRunner, Duration delay, String... commands) throws Exception {
    ArrayDeque<String> queue = new ArrayDeque<String>(ImmutableList.copyOf(commands));
    BufferedReader bufferedReader = mock(BufferedReader.class);
    when(bufferedReader.readLine()).thenAnswer((x) -> {
      clock.advanceBy(delay);
      if (queue.isEmpty()) {
        throw new IOException();
      }
      return queue.poll();
    });
    return new ShellCommand(bufferedReader, clock, commandRunner);
  }

  @Test
  public void testCommandProcessing() throws Exception {
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.ZERO, "test1 foo bar", "test2 foo bar");
    shellCommand.run();
    assertThat(cli.calls)
        .containsExactly(
            ImmutableList.of("test1", "foo", "bar"), ImmutableList.of("test2", "foo", "bar"))
        .inOrder();
  }

  @Test
  public void testNoIdleWhenInAlpha() throws Exception {
    RegistryToolEnvironment.ALPHA.setup();
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardDays(1), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  public void testNoIdleWhenInSandbox() throws Exception {
    RegistryToolEnvironment.SANDBOX.setup();
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardDays(1), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  @Test
  public void testIdleWhenOverHourInProduction() throws Exception {
    RegistryToolEnvironment.PRODUCTION.setup();
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardMinutes(61), "test1 foo bar", "test2 foo bar");
    RuntimeException exception = assertThrows(RuntimeException.class, shellCommand::run);
    assertThat(exception).hasMessageThat().contains("Been idle for too long");
  }

  @Test
  public void testNoIdleWhenUnderHourInProduction() throws Exception {
    RegistryToolEnvironment.PRODUCTION.setup();
    MockCli cli = new MockCli();
    ShellCommand shellCommand =
        createShellCommand(cli, Duration.standardMinutes(59), "test1 foo bar", "test2 foo bar");
    shellCommand.run();
  }

  static class MockCli implements CommandRunner {
    public ArrayList<ImmutableList<String>> calls = new ArrayList<>();

    @Override
    public void run(String[] args) throws Exception {
      calls.add(ImmutableList.copyOf(args));
    }
  }

  @Test
  public void testMultipleCommandInvocations() throws Exception {
    try (RegistryCli cli =
        new RegistryCli("unittest", ImmutableMap.of("test_command", TestCommand.class))) {
      RegistryToolEnvironment.UNITTEST.setup();
      cli.setEnvironment(RegistryToolEnvironment.UNITTEST);
      cli.run(new String[] {"test_command", "-x", "xval", "arg1", "arg2"});
      cli.run(new String[] {"test_command", "-x", "otherxval", "arg3"});
      cli.run(new String[] {"test_command"});
      assertThat(TestCommand.commandInvocations)
          .containsExactly(
              ImmutableList.of("xval", "arg1", "arg2"),
              ImmutableList.of("otherxval", "arg3"),
              ImmutableList.of("default value"));
    }
  }

  @Test
  public void testNonExistentCommand() throws Exception {
    try (RegistryCli cli =
        new RegistryCli("unittest", ImmutableMap.of("test_command", TestCommand.class))) {

      cli.setEnvironment(RegistryToolEnvironment.UNITTEST);
      assertThrows(MissingCommandException.class, () -> cli.run(new String[] {"bad_command"}));
    }
  }

  private void performJCommanderCompletorTest(
      String line,
      int expectedBackMotion,
      String... expectedCompletions) {
    JCommander jcommander = new JCommander();
    jcommander.setProgramName("test");
    jcommander.addCommand("help", new HelpCommand(jcommander));
    jcommander.addCommand("testCommand", new TestCommand());
    jcommander.addCommand("testAnotherCommand", new TestAnotherCommand());
    List<String> completions = new ArrayList<>();
    assertThat(
            line.length()
                - new JCommanderCompletor(jcommander)
                    .completeInternal(line, line.length(), completions))
        .isEqualTo(expectedBackMotion);
    assertThat(completions).containsExactlyElementsIn(expectedCompletions);
  }

  @Test
  public void testCompletion_commands() throws Exception {
    performJCommanderCompletorTest("", 0, "testCommand ", "testAnotherCommand ", "help ");
    performJCommanderCompletorTest("n", 1);
    performJCommanderCompletorTest("test", 4, "testCommand ", "testAnotherCommand ");
    performJCommanderCompletorTest(" test", 4, "testCommand ", "testAnotherCommand ");
    performJCommanderCompletorTest("testC", 5, "testCommand ");
    performJCommanderCompletorTest("testA", 5, "testAnotherCommand ");
  }

  @Test
  public void testCompletion_help() throws Exception {
    performJCommanderCompletorTest("h", 1, "help ");
    performJCommanderCompletorTest("help ", 0, "testCommand ", "testAnotherCommand ", "help ");
    performJCommanderCompletorTest("help testC", 5, "testCommand ");
    performJCommanderCompletorTest("help testCommand ", 0);
  }

  @Test
  public void testCompletion_documentation() throws Exception {
    performJCommanderCompletorTest(
        "testCommand ",
        0,
        "",
        "Main parameter: normal argument\n  (java.util.List<java.lang.String>)");
    performJCommanderCompletorTest("testAnotherCommand ", 0, "", "Main parameter: [None]");
    performJCommanderCompletorTest(
        "testCommand -x ", 0, "", "Flag documentation: test parameter\n  (java.lang.String)");
    performJCommanderCompletorTest(
        "testAnotherCommand -x ", 0, "", "Flag documentation: [No documentation available]");
    performJCommanderCompletorTest(
        "testCommand x ",
        0,
        "",
        "Main parameter: normal argument\n  (java.util.List<java.lang.String>)");
    performJCommanderCompletorTest("testAnotherCommand x ", 0, "", "Main parameter: [None]");
  }

  @Test
  public void testCompletion_arguments() throws Exception {
    performJCommanderCompletorTest("testCommand -", 1, "-x ", "--xparam ", "--xorg ");
    performJCommanderCompletorTest("testCommand --wrong", 7);
    performJCommanderCompletorTest("testCommand noise  --", 2, "--xparam ", "--xorg ");
    performJCommanderCompletorTest("testAnotherCommand --o", 3);
  }

  @Test
  public void testCompletion_enum() throws Exception {
    performJCommanderCompletorTest("testCommand --xorg P", 1, "PRIVATE ", "PUBLIC ");
    performJCommanderCompletorTest("testCommand --xorg PU", 2, "PUBLIC ");
    performJCommanderCompletorTest(
        "testCommand --xorg ", 0, "", "Flag documentation: test organization\n  (PRIVATE, PUBLIC)");
  }

  @Parameters(commandDescription = "Test command")
  static class TestCommand implements Command {
    enum OrgType {
      PRIVATE,
      PUBLIC
    }

    @Parameter(
      names = {"-x", "--xparam"},
      description = "test parameter"
    )
    String xparam = "default value";

    @Parameter(
      names = {"--xorg"},
      description = "test organization"
    )
    OrgType orgType = OrgType.PRIVATE;

    // List for recording command invocations by run().
    //
    // This has to be static because it gets populated by multiple TestCommand instances, which are
    // created in RegistryCli by using reflection to call the constructor.
    static final List<List<String>> commandInvocations = new ArrayList<>();

    @Parameter(description = "normal argument")
    List<String> args;

    public TestCommand() {}

    @Override
    public void run() {
      ImmutableList.Builder<String> callRecord = new ImmutableList.Builder<>();
      callRecord.add(xparam);
      if (args != null) {
        callRecord.addAll(args);
      }
      commandInvocations.add(callRecord.build());
    }
  }

  @Parameters(commandDescription = "Another test command")
  static class TestAnotherCommand implements Command {
    @Override
    public void run() {}
  }
}
