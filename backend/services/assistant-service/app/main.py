from fastapi import FastAPI, APIRouter

app = FastAPI(
    title="assistant-service",
    docs_url="/api/assistant/docs",
    redoc_url="/api/assistant/redoc",
    openapi_url="/api/assistant/openapi.json"
)

api_router = APIRouter(prefix="/api/assistant")


@api_router.get("/health")
def health_check():
    return {"status": "UP"}

app.include_router(api_router)