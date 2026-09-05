"""Validazione dei token Keycloak.

Equivalente Python di quello che nei servizi Java fanno
`spring-boot-starter-oauth2-resource-server` e KeycloakRealmRolesConverter:
la firma si verifica con le chiavi pubbliche pubblicate dall'identity
provider (JWKS), quindi non c'e' nessun segreto condiviso.
"""
import logging
import os
from dataclasses import dataclass, field
from functools import lru_cache
from typing import List, Optional

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import PyJWKClient

logger = logging.getLogger(__name__)

# auto_error=False per distinguere i due casi come fanno gli altri servizi:
# manca il token -> 401, token valido ma ruolo insufficiente -> 403.
# Con il comportamento predefinito sarebbero entrambi 403.
_bearer = HTTPBearer(auto_error=False)


@dataclass
class Principal:
    username: Optional[str]
    email: Optional[str]
    roles: List[str] = field(default_factory=list)

    def has_any_role(self, roles) -> bool:
        return any(role in self.roles for role in roles)


def issuer_uri() -> str:
    return os.getenv("KEYCLOAK_ISSUER_URI", "http://localhost:8180/realms/polyglot-commerce")


@lru_cache(maxsize=1)
def _jwk_client() -> PyJWKClient:
    # Le chiavi vengono scaricate alla prima richiesta con token, non
    # all'avvio: il servizio parte anche se Keycloak non e' ancora pronto.
    return PyJWKClient(f"{issuer_uri()}/protocol/openid-connect/certs")


def current_principal(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(_bearer),
) -> Principal:
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    try:
        signing_key = _jwk_client().get_signing_key_from_jwt(credentials.credentials)
        claims = jwt.decode(
            credentials.credentials,
            signing_key.key,
            algorithms=["RS256"],
            issuer=issuer_uri(),
            # Keycloak mette in "aud" il client destinatario ("account" di
            # default): non e' un vincolo utile finche' tutti i servizi
            # accettano i token dello stesso realm, come fanno anche i
            # resource server Spring di questo progetto.
            options={"verify_aud": False},
        )
    except jwt.PyJWTError as error:
        logger.info("Rejected token: %s", error)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return Principal(
        username=claims.get("preferred_username"),
        email=claims.get("email"),
        roles=list(claims.get("realm_access", {}).get("roles", [])),
    )


def require_roles(*roles: str):
    """Dipendenza FastAPI che pretende almeno uno dei ruoli indicati."""

    def dependency(principal: Principal = Depends(current_principal)) -> Principal:
        if not principal.has_any_role(roles):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Requires one of the roles: {', '.join(roles)}",
            )
        return principal

    return dependency
