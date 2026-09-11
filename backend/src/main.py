"""FastAPI application entry point."""
from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sqlmodel import select
from sqlalchemy import text

from src.auth.router import router as auth_router
from src.admin.router import router as admin_router
from src.tokens.router import router as tokens_router
from src.tokens.admin_router import router as admin_tokens_router
from src.v2.router import router as v2_router
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from src.database import get_engine, get_session
from src.auth.models import User
from src.auth.service import get_password_hash
from src.config import get_settings


async def _create_admin_if_missing():
    """Create an admin only when explicit bootstrap credentials are configured."""
    settings = get_settings()
    if not settings.ADMIN_USERNAME or not settings.ADMIN_PASSWORD:
        return
    engine = get_engine()
    async with async_sessionmaker(
        bind=engine, class_=AsyncSession, expire_on_commit=False
    )() as session:
        result = await session.execute(select(User).where(User.is_admin == True))
        if result.scalars().first() is not None:
            return

        admin = User(
            username=settings.ADMIN_USERNAME,
            password_hash=get_password_hash(settings.ADMIN_PASSWORD),
            is_admin=True,
        )
        session.add(admin)
        await session.commit()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifespan context manager for startup/shutdown events."""
    # The schema is migrated explicitly before the application starts.
    await _create_admin_if_missing()
    yield
    # Shutdown: cleanup if needed


app = FastAPI(
    title="DayForge API",
    description="Backend API for DayForge application",
    version="0.1.0",
    lifespan=lifespan
)

# CORS middleware
settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers with /api/v1 prefix
app.include_router(auth_router, prefix='/api/v1')
app.include_router(admin_router, prefix='/api/v1')
app.include_router(tokens_router, prefix="/api/v1")
app.include_router(admin_tokens_router, prefix="/api/v1")
app.include_router(v2_router)


@app.get("/health")
async def health_check(session: AsyncSession = Depends(get_session)):
    """Report ready only when the SQLite database is reachable."""
    await session.execute(text("SELECT 1"))
    return {"status": "ok"}


@app.get("/")
async def root():
    """Root endpoint."""
    return {"message": "DayForge API", "status": "running"}
