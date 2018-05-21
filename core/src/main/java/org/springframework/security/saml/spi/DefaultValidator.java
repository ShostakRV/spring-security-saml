/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.springframework.security.saml.spi;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.springframework.security.saml.MetadataResolver;
import org.springframework.security.saml.SamlValidator;
import org.springframework.security.saml.key.SimpleKey;
import org.springframework.security.saml.saml2.Saml2Object;
import org.springframework.security.saml.saml2.authentication.Assertion;
import org.springframework.security.saml.saml2.authentication.AssertionCondition;
import org.springframework.security.saml.saml2.authentication.AudienceRestriction;
import org.springframework.security.saml.saml2.authentication.AuthenticationStatement;
import org.springframework.security.saml.saml2.authentication.Conditions;
import org.springframework.security.saml.saml2.authentication.Issuer;
import org.springframework.security.saml.saml2.authentication.NameIdPrincipal;
import org.springframework.security.saml.saml2.authentication.Response;
import org.springframework.security.saml.saml2.authentication.StatusCode;
import org.springframework.security.saml.saml2.authentication.SubjectConfirmation;
import org.springframework.security.saml.saml2.authentication.SubjectConfirmationData;
import org.springframework.security.saml.saml2.metadata.Endpoint;
import org.springframework.security.saml.saml2.metadata.IdentityProviderMetadata;
import org.springframework.security.saml.saml2.metadata.ServiceProviderMetadata;
import org.springframework.security.saml.saml2.signature.Signature;
import org.springframework.security.saml.saml2.signature.SignatureException;
import org.springframework.security.saml.validation.ValidationException;
import org.springframework.security.saml.validation.ValidationResult;
import org.springframework.security.saml.validation.ValidationResult.ValidationError;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.springframework.security.saml.saml2.authentication.SubjectConfirmationMethod.BEARER;
import static org.springframework.security.saml.saml2.metadata.NameId.ENTITY;
import static org.springframework.util.StringUtils.hasText;

public class DefaultValidator implements SamlValidator {

    private SpringSecuritySaml implementation;
    private int responseSkewTimeMillis = 120000;
    private boolean allowUnsolicitedResponses = true;
    private int maxAuthenticationAgeMillis;
    private Clock time = Clock.systemUTC();

    public DefaultValidator(SpringSecuritySaml implementation) {
        setImplementation(implementation);
    }

    private void setImplementation(SpringSecuritySaml implementation) {
        this.implementation = implementation;
    }

    public int getResponseSkewTimeMillis() {
        return responseSkewTimeMillis;
    }

    public DefaultValidator setResponseSkewTimeMillis(int responseSkewTimeMillis) {
        this.responseSkewTimeMillis = responseSkewTimeMillis;
        return this;
    }

    public boolean isAllowUnsolicitedResponses() {
        return allowUnsolicitedResponses;
    }

    public DefaultValidator setAllowUnsolicitedResponses(boolean allowUnsolicitedResponses) {
        this.allowUnsolicitedResponses = allowUnsolicitedResponses;
        return this;
    }

    @Override
    public Signature validateSignature(Saml2Object saml2Object, List<SimpleKey> verificationKeys)
        throws SignatureException {
        try {
            return implementation.validateSignature(saml2Object, verificationKeys);
        } catch (Exception x) {
            if (x instanceof SignatureException) {
                throw x;
            } else {
                throw new SignatureException(x.getMessage(), x);
            }
        }
    }

    @Override
    public void validate(Saml2Object saml2Object, MetadataResolver resolver) throws ValidationException {

    }


    protected ValidationResult validate(Response response,
                                        List<String> mustMatchInResponseTo,
                                        ServiceProviderMetadata requester,
                                        IdentityProviderMetadata responder) {
        boolean wantAssertionsSigned = requester.getServiceProvider().isWantAssertionsSigned();
        String entityId = requester.getEntityId();

        if (response == null) {
            return new ValidationResult().addError(new ValidationError("Response is null"));
        }

        if (response.getStatus() == null || response.getStatus().getCode() == null) {
            return new ValidationResult().addError(new ValidationError("Response status or code is null"));
        }

        StatusCode statusCode = response.getStatus().getCode();
        if (statusCode != StatusCode.SUCCESS) {
            return new ValidationResult().addError(
                new ValidationError("An error response was returned: " + statusCode.toString())
            );
        }

        if (response.getSignature() != null && !response.getSignature().isValidated()) {
            return new ValidationResult().addError(new ValidationError("No validated signature present"));
        }

        //verify issue time
        DateTime issueInstant = response.getIssueInstant();
        if (!isDateTimeSkewValid(getResponseSkewTimeMillis(), 0, issueInstant)) {
            return new ValidationResult().addError(new ValidationError("Issue time is either too old or in the future:"+issueInstant.toString()));
        }

        //validate InResponseTo
        String replyTo = response.getInResponseTo();
        if (!isAllowUnsolicitedResponses() && !hasText(replyTo)) {
            return new ValidationResult().addError(new ValidationError("InResponseTo is missing and unsolicited responses are disabled"));
        }

        if (hasText(replyTo) && !mustMatchInResponseTo.contains(replyTo)) {
            return new ValidationResult().addError(new ValidationError("Invalid InResponseTo ID, not found in supplied list"));
        }

        //validate destination
        if (!compareURIs(requester.getServiceProvider().getAssertionConsumerService(), response.getDestination())) {
            return new ValidationResult().addError(new ValidationError("Destination mismatch: " + response.getDestination()));
        }

        //validate issuer
        //name id if not null should be "urn:oasis:names:tc:SAML:2.0:nameid-format:entity"
        //value should be the entity ID of the responder
        ValidationResult result = verifyIssuer(response.getIssuer(), requester);
        if (result != null) {
            return result;
        }

        Assertion validAssertion = null;
        ValidationResult assertionValidation = new ValidationResult();
        //DECRYPT ENCRYPTED ASSERTIONS
        for (Assertion assertion : response.getAssertions()) {
            //verify assertion
            //issuer
            //signature
            if (wantAssertionsSigned && (assertion.getSignature()==null || !assertion.getSignature().isValidated())) {
                return new ValidationResult().addError(new ValidationError("Assertion is not signed"));
            }

            for (SubjectConfirmation conf : assertion.getSubject().getConfirmations()) {
                assertionValidation.setErrors(emptyList());

                //verify assertion subject for BEARER
                if (!BEARER.equals(conf.getMethod())) {
                    assertionValidation.addError(new ValidationError("Invalid confirmation method:"+conf.getMethod()));
                    continue;
                }

                //for each subject confirmation data
                //1. data must not be null
                SubjectConfirmationData data = conf.getConfirmationData();
                if (data == null) {
                    assertionValidation.addError(new ValidationError("Empty subject confirmation data."));
                    continue;
                }


                //2. NotBefore must be null (saml-profiles-2.0-os 558)
                // Not before forbidden by saml-profiles-2.0-os 558
                if (data.getNotBefore() != null) {
                    assertionValidation.addError(new ValidationError("Subject confirmation data should not have NotBefore date."));
                    continue;
                }
                //3. NotOnOfAfter must not be null and within skew
                if (data.getNotOnOrAfter() == null) {
                    assertionValidation.addError(new ValidationError("Subject confirmation data is missing NotOnOfAfter date."));
                    continue;
                }

                if (data.getNotOnOrAfter().plusMillis(getResponseSkewTimeMillis()).isBeforeNow()) {
                    assertionValidation.addError(
                        new ValidationError(
                            String.format("Invalid NotOnOrAfter date: %s", data.getNotOnOrAfter())
                        )
                    );
                }
                //4. InResponseTo if it exists
                if (hasText(data.getInResponseTo())) {
                    if (mustMatchInResponseTo != null) {
                        if (!mustMatchInResponseTo.contains(data.getInResponseTo())) {
                            assertionValidation.addError(
                                new ValidationError(
                                    String.format("No match for InResponseTo: %s found.", data.getInResponseTo())
                                )
                            );
                            continue;
                        }
                    } else if (!isAllowUnsolicitedResponses()) {
                        assertionValidation.addError(new ValidationError("InResponseTo missing and system not configured to allow unsolicited messages"));
                        continue;
                    }
                }
                //5. Recipient must match ACS URL
                if (!hasText(data.getRecipient())) {
                    assertionValidation.addError(new ValidationError("Assertion Recipient field missing"));
                    continue;
                } else if (!compareURIs(requester.getServiceProvider().getAssertionConsumerService(), data.getRecipient())) {
                    assertionValidation.addError(new ValidationError("Invalid assertion Recipient field: "+data.getRecipient()));
                    continue;
                }
                //6. DECRYPT NAMEID if it is encrypted
                //6b. Use regular NameID
                if ( ((NameIdPrincipal) assertion.getSubject().getPrincipal()) != null) {
                    //we have a valid assertion, that's the one we will be using
                    validAssertion = assertion;
                    break;
                }
            }
        }
        if (validAssertion == null) {
            assertionValidation.addError(new ValidationError("No valid assertion with principal found."));
            return assertionValidation;
        }

        for (AuthenticationStatement statement : ofNullable(validAssertion.getAuthenticationStatements()).orElse(emptyList())) {
            //VERIFY authentication statements
            if (!isDateTimeSkewValid(getResponseSkewTimeMillis(), getMaxAuthenticationAgeMillis(), statement.getAuthInstant())) {
                return new ValidationResult()
                    .addError("Authentication statement is too old to be used with value " + statement.getAuthInstant());
            }

            if (statement.getSessionNotOnOrAfter() != null && statement.getSessionNotOnOrAfter().isAfterNow()) {
                return new ValidationResult()
                    .addError("Authentication session expired on: " + statement.getSessionNotOnOrAfter());
            }

            //possibly check the
            //statement.getAuthenticationContext().getClassReference()
        }

        Conditions conditions = validAssertion.getConditions();
        if (conditions != null) {
            //VERIFY conditions
            if (conditions.getNotBefore()!=null && conditions.getNotBefore().minusMillis(getResponseSkewTimeMillis()).isAfterNow()) {
                return new ValidationResult()
                    .addError("Conditions expired (not before): " + conditions.getNotBefore());
            }

            if (conditions.getNotOnOrAfter()!=null && conditions.getNotOnOrAfter().plusMillis(getResponseSkewTimeMillis()).isBeforeNow()) {
                return new ValidationResult()
                    .addError("Conditions expired (not on or after): " + conditions.getNotOnOrAfter());
            }

            for (AssertionCondition c : conditions.getCriteria()) {
                if (c instanceof AudienceRestriction) {
                    AudienceRestriction ac = (AudienceRestriction)c;
                    ac.evaluate(entityId, time());
                    if (!ac.isValid()) {
                        return new ValidationResult()
                            .addError(
                                String.format("Audience restriction evaluation failed for assertion condition. Expected %s Was %s",
                                              entityId,
                                              ac.getAudiences()
                                )
                            );
                    }
                }
            }
        }







        return new ValidationResult();

    }

    public Clock time() {
        return null;
    }

    public DefaultValidator setTime(Clock time) {
        this.time = time;
        return this;
    }

    protected ValidationResult verifyIssuer(Issuer issuer, ServiceProviderMetadata requester) {
        if (issuer != null) {
            if (!requester.getEntityId().equals(issuer.getValue())) {
                return new ValidationResult()
                    .addError(
                        new ValidationError(
                            String.format("Issuer mismatch. Expected: %s Actual: %s",
                                          requester.getEntityId(), issuer.getValue())
                        )
                    );
            }
            if (issuer.getFormat() != null && !issuer.getFormat().equals(ENTITY)) {
                return new ValidationResult()
                    .addError(
                        new ValidationError(
                            String.format("Issuer name format mismatch. Expected: %s Actual: %s",
                                          ENTITY, issuer.getFormat())
                        )
                    );
            }
        }
        return null;
    }

    protected boolean isDateTimeSkewValid(int skewMillis, int forwardMillis, DateTime time) {
        if (time == null) {
            return false;
        }
        final DateTime reference = new DateTime();
        final Interval validTimeInterval = new Interval(
            reference.minusMillis(skewMillis + forwardMillis),
            reference.plusMillis(skewMillis)
        );
        return validTimeInterval.contains(time);
    }

    protected boolean compareURIs(List<Endpoint> endpoints, String uri) {
        for (Endpoint ep : endpoints) {
            if (compareURIs(ep.getLocation(), uri)) {
                return true;
            }
        }
        return false;
    }

    protected boolean compareURIs(String uri1, String uri2) {
        if (uri1 == null && uri2 == null) {
            return true;
        }
        try {
            new URI(uri1);
            new URI(uri2);
            return removeQueryString(uri1).equalsIgnoreCase(removeQueryString(uri2));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public String removeQueryString(String uri) {
        int queryStringIndex = uri.indexOf('?');
        if (queryStringIndex >= 0) {
            return uri.substring(0, queryStringIndex);
        }
        return uri;
    }

    public int getMaxAuthenticationAgeMillis() {
        return maxAuthenticationAgeMillis;
    }

    public void setMaxAuthenticationAgeMillis(int maxAuthenticationAgeMillis) {
        this.maxAuthenticationAgeMillis = maxAuthenticationAgeMillis;
    }
}