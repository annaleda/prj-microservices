import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { AppComponent } from './app.component';
import { AuthService } from './services/auth.service';

describe('AppComponent', () => {
  // AppComponent mostra il nome dell'utente e i comandi di accesso:
  // qui basta un AuthService finto, il login vero non c'entra.
  const authStub = {
    isLoggedIn: false,
    username: null,
    login: () => undefined,
    logout: () => undefined,
  };

  beforeEach(() => TestBed.configureTestingModule({
    imports: [RouterTestingModule],
    declarations: [AppComponent],
    providers: [{ provide: AuthService, useValue: authStub }]
  }));

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it(`should have as title 'customer-web'`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app.title).toEqual('customer-web');
  });

  it('should render the brand link', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-header__brand')?.textContent).toContain('Polyglot Commerce');
  });
});
