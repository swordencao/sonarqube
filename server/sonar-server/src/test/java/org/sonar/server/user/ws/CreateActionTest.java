/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.user.ws;

import com.google.common.base.Throwables;
import java.io.IOException;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.AlwaysIncreasingSystem2;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.ServerException;
import org.sonar.server.organization.OrganizationCreation;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.user.NewUserNotifier;
import org.sonar.server.user.UserUpdater;
import org.sonar.server.user.index.UserDoc;
import org.sonar.server.user.index.UserIndex;
import org.sonar.server.user.index.UserIndexDefinition;
import org.sonar.server.user.index.UserIndexer;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.WsUsers.CreateWsResponse;
import org.sonarqube.ws.WsUsers.CreateWsResponse.User;
import org.sonarqube.ws.client.user.CreateRequest;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.sonar.core.util.Protobuf.setNullable;
import static org.sonar.db.user.UserTesting.newUserDto;

public class CreateActionTest {

  private static final String DEFAULT_GROUP_NAME = "sonar-users";
  private Settings settings = new MapSettings().setProperty("sonar.defaultGroup", DEFAULT_GROUP_NAME);
  private System2 system2 = new AlwaysIncreasingSystem2();

  @Rule
  public DbTester db = DbTester.create(system2);
  @Rule
  public EsTester esTester = new EsTester(new UserIndexDefinition(settings));
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private UserIndex index = new UserIndex(esTester.client());
  private UserIndexer userIndexer = new UserIndexer(db.getDbClient(), esTester.client());
  private GroupDto defaultGroupInDefaultOrg;
  private OrganizationCreation organizationCreation = mock(OrganizationCreation.class);

  private WsActionTester tester = new WsActionTester(new CreateAction(
    new UserUpdater(mock(NewUserNotifier.class), settings, db.getDbClient(), userIndexer, system2, TestDefaultOrganizationProvider.from(db), organizationCreation),
    userSessionRule));

  @Before
  public void setUp() {
    defaultGroupInDefaultOrg = db.users().insertGroup(db.getDefaultOrganization(), DEFAULT_GROUP_NAME);
  }

  @Test
  public void create_user() throws Exception {
    logInAsSystemAdministrator();

    CreateWsResponse response = call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setEmail("john@email.com")
      .setScmAccounts(singletonList("jn"))
      .setPassword("1234")
      .build());

    assertThat(response.getUser())
      .extracting(User::getLogin, User::getName, User::getEmail, User::getScmAccountsList, User::getLocal)
      .containsOnly("john", "John", "john@email.com", singletonList("jn"), true);

    UserDoc user = index.getNullableByLogin("john");
    assertThat(user.login()).isEqualTo("john");
    assertThat(user.name()).isEqualTo("John");
    assertThat(user.email()).isEqualTo("john@email.com");
    assertThat(user.scmAccounts()).containsOnly("jn");

    // exists in db
    Optional<UserDto> dbUser = db.users().selectUserByLogin("john");
    assertThat(dbUser).isPresent();
    assertThat(dbUser.get().isRoot()).isFalse();

    // member of default group in default organization
    assertThat(db.users().selectGroupIdsOfUser(dbUser.get())).containsOnly(defaultGroupInDefaultOrg.getId());
  }

  @Test
  public void create_local_user() throws Exception {
    logInAsSystemAdministrator();

    call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setPassword("1234")
      .setLocal(true)
      .build());

    assertThat(db.users().selectUserByLogin("john").get())
      .extracting(UserDto::isLocal, UserDto::getExternalIdentityProvider, UserDto::getExternalIdentity, UserDto::isRoot)
      .containsOnly(true, "sonarqube", "john", false);
  }

  @Test
  public void create_none_local_user() throws Exception {
    logInAsSystemAdministrator();

    call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setLocal(false)
      .build());

    assertThat(db.users().selectUserByLogin("john").get())
      .extracting(UserDto::isLocal, UserDto::getExternalIdentityProvider, UserDto::getExternalIdentity, UserDto::isRoot)
      .containsOnly(false, "sonarqube", "john", false);
  }

  @Test
  public void create_user_with_comma_in_scm_account() throws Exception {
    logInAsSystemAdministrator();

    CreateWsResponse response = call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setEmail("john@email.com")
      .setScmAccounts(singletonList("j,n"))
      .setPassword("1234")
      .build());

    assertThat(response.getUser().getScmAccountsList()).containsOnly("j,n");
  }

  @Test
  public void create_user_with_deprecated_scmAccounts_parameter() throws Exception {
    logInAsSystemAdministrator();

    tester.newRequest()
      .setParam("login", "john")
      .setParam("name", "John")
      .setParam("password", "1234")
      .setParam("scmAccounts", "jn")
      .execute();

    assertThat(db.users().selectUserByLogin("john").get().getScmAccountsAsList()).containsOnly("jn");
  }

  @Test
  public void create_user_with_deprecated_scm_accounts_parameter() throws Exception {
    logInAsSystemAdministrator();

    tester.newRequest()
      .setParam("login", "john")
      .setParam("name", "John")
      .setParam("password", "1234")
      .setParam("scm_accounts", "jn")
      .execute();

    assertThat(db.users().selectUserByLogin("john").get().getScmAccountsAsList()).containsOnly("jn");
  }

  @Test
  public void reactivate_user() throws Exception {
    logInAsSystemAdministrator();

    UserDto userDto = newUserDto("john", "John", "john@email.com");
    db.users().insertUser(userDto);
    db.getDbClient().userDao().deactivateUserByLogin(db.getSession(), "john");
    db.commit();
    userIndexer.index(userDto.getLogin());

    call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setEmail("john@email.com")
      .setScmAccounts(singletonList("jn"))
      .setPassword("1234")
      .build());

    assertThat(db.users().selectUserByLogin("john").get().isActive()).isTrue();
  }

  @Test
  public void request_fails_with_ServerException_when_default_group_belongs_to_another_organization() throws Exception {
    OrganizationDto otherOrganization = db.organizations().insert();
    GroupDto group = db.users().insertGroup(otherOrganization);
    setDefaultGroupProperty(group);
    logInAsSystemAdministrator();

    expectedException.expect(ServerException.class);
    expectedException.expectMessage("The default group '" + group.getName() + "' for new users does not exist. " +
      "Please update the general security settings to fix this issue");

    executeRequest("bar");
  }

  @Test
  public void fail_when_missing_login() throws Exception {
    logInAsSystemAdministrator();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Login is mandatory and must not be empty");
    call(CreateRequest.builder()
      .setLogin(null)
      .setName("John")
      .setPassword("1234")
      .build());
  }

  @Test
  public void fail_when_missing_name() throws Exception {
    logInAsSystemAdministrator();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Name is mandatory and must not be empty");
    call(CreateRequest.builder()
      .setLogin("john")
      .setName(null)
      .setPassword("1234")
      .build());
  }

  @Test
  public void fail_when_missing_password() throws Exception {
    logInAsSystemAdministrator();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Password is mandatory and must not be empty");
    call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setPassword(null)
      .build());
  }

  @Test
  public void fail_when_password_is_set_on_none_local_user() throws Exception {
    logInAsSystemAdministrator();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Password should only be set on local user");
    call(CreateRequest.builder()
      .setLogin("john")
      .setName("John")
      .setPassword("1234")
      .setLocal(false)
      .build());
  }

  @Test
  public void throw_ForbiddenException_if_not_system_administrator() throws Exception {
    userSessionRule.logIn().setNonSystemAdministrator();

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("");

    expectedException.expect(ForbiddenException.class);
    executeRequest("john");
  }

  private void setDefaultGroupProperty(GroupDto adminGroup) {
    settings.setProperty("sonar.defaultGroup", adminGroup.getName());
  }

  private CreateWsResponse executeRequest(String login) throws Exception {
    return call(CreateRequest.builder()
      .setLogin(login)
      .setName("name of " + login)
      .setEmail(login + "@email.com")
      .setScmAccounts(singletonList(login.substring(0, 2)))
      .setPassword("pwd_" + login)
      .build());
  }

  private void logInAsSystemAdministrator() {
    userSessionRule.logIn().setSystemAdministrator();
  }

  private CreateWsResponse call(CreateRequest createRequest) {
    TestRequest request = tester.newRequest()
      .setMediaType(MediaTypes.PROTOBUF);
    setNullable(createRequest.getLogin(), e -> request.setParam("login", e));
    setNullable(createRequest.getName(), e -> request.setParam("name", e));
    setNullable(createRequest.getEmail(), e -> request.setParam("email", e));
    setNullable(createRequest.getPassword(), e -> request.setParam("password", e));
    setNullable(createRequest.getScmAccounts(), e -> request.setMultiParam("scmAccount", e));
    request.setParam("local", createRequest.isLocal() ? "true" : "false");
    try {
      return CreateWsResponse.parseFrom(request.execute().getInputStream());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }
}
