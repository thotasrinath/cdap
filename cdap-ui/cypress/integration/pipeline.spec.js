/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

import { loginIfRequired, getAuthHeaders } from '../helpers';

const TEST_PIPELINE_NAME = '__UI_test_pipeline';
const TEST_PATH = '__UI_test_path';

function getArtifactsPoll(headers, retries = 0) {
  if (retries === 3) {
    return;
  }
  cy.request({
    method: 'GET',
    url: `http://${Cypress.env('host')}:11015/v3/namespaces/default/artifacts?scope=SYSTEM`,
    failOnStatusCode: false,
    headers,
  }).then((response) => {
    if (response.status >= 400) {
      return getArtifactsPoll(headers, retries + 1);
    }
    return;
  });
}

function cleanUpPipeline(headers) {
  cy.request({
    method: 'GET',
    url: `http://${Cypress.env('host')}:11015/v3/namespaces/default/apps/${TEST_PIPELINE_NAME}`,
    failOnStatusCode: false,
    headers,
  }).then((response) => {
    if (response.status === 200) {
      cy.request({
        method: 'DELETE',
        url: `http://${Cypress.env('host')}:11015/v3/namespaces/default/apps/${TEST_PIPELINE_NAME}`,
        failOnStatusCode: false,
        headers,
      });
    }
  });
}

describe('Creating a pipeline', function() {
  // Uses API call to login instead of logging in manually through UI
  before(function() {
    loginIfRequired();
  });

  beforeEach(function() {
    const headers = getAuthHeaders();
    // Delete TEST_PIPELINE_NAME pipeline in case it's already there
    cleanUpPipeline(headers);
    getArtifactsPoll(headers);
  });

  afterEach(function() {
    // Delete the pipeline to clean up
    const headers = getAuthHeaders();
    cleanUpPipeline(headers);
  });

  it('is configured correctly', function() {
    // Go to Pipelines studio
    cy.visit('/');
    cy.get('#resource-center-btn').click();
    cy.contains('Create').click();
    cy.url().should('include', '/studio');

    // Add an action node, to create minimal working pipeline
    cy.get('.item')
      .contains('Conditions and Actions')
      .click();
    cy.get('.item-body-wrapper')
      .contains('File Delete')
      .click();

    // Fill out required Path input field with some test value
    cy.get('.node-configure-btn')
      .invoke('show')
      .click();
    cy.get('.form-group')
      .contains('Path')
      .parent()
      .find('input')
      .click()
      .type(TEST_PATH);

    cy.get('[data-testid=close-config-popover]').click();

    // Click on Configure, toggle Instrumentation value, then Save
    cy.contains('Configure').click();
    cy.get('.label-with-toggle')
      .contains('Instrumentation')
      .parent()
      .as('instrumentationDiv');
    cy.get('@instrumentationDiv').contains('On');
    cy.get('@instrumentationDiv')
      .find('.toggle-switch')
      .click();
    cy.get('@instrumentationDiv').contains('Off');
    cy.get('[data-testid=config-apply-close]').click();

    // Name pipeline then deploy pipeline
    cy.get('.pipeline-name').click();
    cy.get('#pipeline-name-input')
      .type(TEST_PIPELINE_NAME)
      .type('{enter}');
    cy.get('[data-testid=deploy-pipeline]').click();

    // Do assertions
    cy.url({ timeout: 10000 }).should('include', `/view/${TEST_PIPELINE_NAME}`);
    cy.contains(TEST_PIPELINE_NAME);
    cy.contains('FileDelete');
    cy.contains('Configure').click();
    cy.contains('Pipeline config').click();
    cy.get('.label-with-toggle')
      .contains('Instrumentation')
      .parent()
      .as('instrumentationDiv');
    cy.get('@instrumentationDiv').contains('Off');
    cy.get('[data-testid=close-modeless]').click();
  });
});
